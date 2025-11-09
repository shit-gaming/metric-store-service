package com.metricstore.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.metricstore.domain.entity.ColdStorageMetadata
import com.metricstore.domain.entity.MetricSample
import com.metricstore.repository.ColdStorageMetadataRepository
import com.metricstore.repository.MetricSampleRepository
import io.minio.MinioClient
import io.minio.PutObjectArgs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

private val logger = KotlinLogging.logger {}

/**
 * Service responsible for archiving old metric data to cold storage (S3/MinIO)
 * Implements careful batching and throttling to avoid database performance issues
 * Uses JSON with GZIP compression for simplicity
 */
@Service
class ColdStorageArchivalService(
    private val metricSampleRepository: MetricSampleRepository,
    private val coldStorageMetadataRepository: ColdStorageMetadataRepository,
    private val minioClient: MinioClient,
    private val objectMapper: ObjectMapper,
    @Value("\${metric-store.storage.cold-tier.s3.bucket-name:cold-storage}")
    private val bucketName: String,
    @Value("\${metric-store.storage.cold-tier.retention-days:30}")
    private val retentionDays: Long,
    @Value("\${metric-store.archival.batch-size:5000}")
    private val batchSize: Int,
    @Value("\${metric-store.archival.delay-between-batches-ms:1000}")
    private val delayBetweenBatches: Long,
    @Value("\${metric-store.archival.max-concurrent-uploads:3}")
    private val maxConcurrentUploads: Int,
    @Value("\${metric-store.archival.vacuum-threshold-rows:100000}")
    private val vacuumThresholdRows: Long,
    @Value("\${metric-store.archival.enabled:false}")
    private val archivalEnabled: Boolean
) {

    private val isArchiving = AtomicBoolean(false)
    private val totalRowsArchived = AtomicLong(0)
    private val totalBytesArchived = AtomicLong(0)
    
    /**
     * Main archival job - runs daily at 2 AM
     * Archives data older than retention period to cold storage
     */
    @Scheduled(cron = "\${metric-store.archival.cron:0 0 2 * * ?}")
    fun archiveOldDataJob() = runBlocking {
        if (!archivalEnabled) {
            logger.info { "Cold storage archival is disabled" }
            return@runBlocking
        }
        
        if (!isArchiving.compareAndSet(false, true)) {
            logger.warn { "Archival job already running, skipping this execution" }
            return@runBlocking
        }
        
        try {
            logger.info { "Starting cold storage archival job" }
            val startTime = System.currentTimeMillis()
            
            // Calculate cutoff date
            val cutoffDate = Instant.now().minusSeconds(retentionDays * 24 * 3600)
            logger.info { "Archiving data older than $cutoffDate" }
            
            // Process each metric separately to avoid large transactions
            val metrics = metricSampleRepository.findDistinctMetricsWithDataBefore(cutoffDate)
                .toList()
            
            logger.info { "Found ${metrics.size} metrics with data to archive" }
            
            // Process metrics with controlled concurrency
            metrics.chunked(maxConcurrentUploads).forEach { metricBatch ->
                coroutineScope {
                    metricBatch.map { metricId ->
                        async {
                            archiveMetricData(metricId, cutoffDate)
                        }
                    }.awaitAll()
                }
                
                // Delay between metric batches to reduce load
                if (metrics.size > maxConcurrentUploads) {
                    delay(delayBetweenBatches)
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            logger.info { 
                "Archival job completed in ${duration}ms. " +
                "Archived ${totalRowsArchived.get()} rows, " +
                "${totalBytesArchived.get() / 1024 / 1024}MB"
            }
            
            // Run vacuum if needed (but not blocking the main job)
            if (totalRowsArchived.get() > vacuumThresholdRows) {
                GlobalScope.launch {
                    performIncrementalVacuum()
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error during archival job" }
        } finally {
            isArchiving.set(false)
            totalRowsArchived.set(0)
            totalBytesArchived.set(0)
        }
    }
    
    /**
     * Archive data for a specific metric
     */
    private suspend fun archiveMetricData(metricId: UUID, cutoffDate: Instant) {
        try {
            val metricIdStr = metricId.toString()
            logger.debug { "Archiving data for metric $metricIdStr" }
            
            // Process in daily chunks to create reasonably sized files
            var currentDate = cutoffDate
            val today = Instant.now()
            
            while (currentDate.isBefore(today)) {
                val startOfDay = currentDate.atZone(ZoneId.of("UTC"))
                    .toLocalDate()
                    .atStartOfDay(ZoneId.of("UTC"))
                    .toInstant()
                
                val endOfDay = startOfDay.plusSeconds(86400)
                
                // Skip if already archived
                if (isAlreadyArchived(metricId, startOfDay)) {
                    logger.debug { "Data for $metricIdStr on $startOfDay already archived" }
                    currentDate = endOfDay
                    continue
                }
                
                // Archive this day's data
                archiveDailyData(metricId, startOfDay, endOfDay)
                
                currentDate = endOfDay
                
                // Small delay between days to reduce load
                delay(100)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error archiving metric $metricId" }
        }
    }
    
    /**
     * Archive a single day's data for a metric
     * Uses JSON + GZIP compression for simplicity and portability
     */
    private suspend fun archiveDailyData(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ) {
        var offset = 0
        var hasMore = true
        val tempFile = Files.createTempFile("metric_${metricId}_", ".json.gz")
        val allSamples = mutableListOf<MetricSample>()
        
        try {
            // Fetch all samples for the day
            while (hasMore) {
                val samples = metricSampleRepository.findByMetricAndTimeRangeWithPagination(
                    metricId = metricId,
                    startTime = startTime,
                    endTime = endTime,
                    limit = batchSize,
                    offset = offset
                ).toList()
                
                if (samples.isEmpty()) {
                    hasMore = false
                } else {
                    allSamples.addAll(samples)
                    offset += samples.size
                    
                    // Small delay to avoid overwhelming the database
                    if (samples.size == batchSize) {
                        delay(50)
                    }
                }
            }
            
            if (allSamples.isNotEmpty()) {
                // Write to compressed JSON file
                ByteArrayOutputStream().use { baos ->
                    GZIPOutputStream(baos).use { gzip ->
                        // Convert samples to JSON
                        val json = objectMapper.writeValueAsString(allSamples.map { sample ->
                            mapOf(
                                "timestamp" to sample.time.toEpochMilli(),
                                "metric_id" to metricId.toString(),
                                "value" to sample.value,
                                "labels" to sample.labels.asString()
                            )
                        })
                        gzip.write(json.toByteArray())
                    }
                    
                    // Write to temp file
                    Files.write(tempFile, baos.toByteArray())
                }
                
                val rowCount = allSamples.size
                val fileSize = Files.size(tempFile)
                
                // Upload to MinIO/S3
                val objectName = generateObjectName(metricId, startTime)
                uploadToStorage(tempFile.toFile(), objectName)
                
                // Save metadata
                saveMetadata(metricId, startTime, endTime, objectName, rowCount, fileSize)
                
                // Delete archived data from hot storage
                deleteArchivedData(metricId, startTime, endTime, rowCount)
                
                totalRowsArchived.addAndGet(rowCount.toLong())
                totalBytesArchived.addAndGet(fileSize)
                
                logger.info { "Archived $rowCount rows for metric $metricId on ${startTime.atZone(ZoneId.of("UTC")).toLocalDate()}" }
            }
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
    
    /**
     * Upload file to MinIO/S3
     */
    private suspend fun uploadToStorage(file: File, objectName: String) {
        try {
            val inputStream = file.inputStream()
            
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectName)
                    .stream(inputStream, file.length(), -1)
                    .contentType("application/octet-stream")
                    .build()
            )
            
            logger.debug { "Uploaded $objectName to $bucketName" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to upload $objectName to storage" }
            throw e
        }
    }
    
    /**
     * Save metadata about archived data
     */
    private suspend fun saveMetadata(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        objectName: String,
        rowCount: Int,
        fileSize: Long
    ) {
        val metadata = ColdStorageMetadata(
            id = UUID.randomUUID(),
            metricId = metricId,
            startTime = startTime,
            endTime = endTime,
            storagePath = objectName,
            fileFormat = "json.gz",
            fileSizeBytes = fileSize,
            rowCount = rowCount.toLong(),
            createdAt = Instant.now()
        )
        
        coldStorageMetadataRepository.save(metadata)
    }
    
    /**
     * Delete archived data from hot storage with controlled batching
     */
    private suspend fun deleteArchivedData(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        batchSize: Int
    ) {
        try {
            // Delete in small batches to avoid long-running transactions
            metricSampleRepository.deleteByMetricAndTimeRangeInBatches(
                metricId = metricId,
                startTime = startTime,
                endTime = endTime,
                batchSize = batchSize
            )
        } catch (e: Exception) {
            logger.error(e) { "Error deleting archived data for metric $metricId" }
            // Don't fail the archival process if deletion fails
            // Data can be cleaned up in a separate maintenance job
        }
    }
    
    /**
     * Check if data is already archived
     */
    private suspend fun isAlreadyArchived(metricId: UUID, date: Instant): Boolean {
        return coldStorageMetadataRepository.existsByMetricIdAndDate(metricId, date)
    }
    
    /**
     * Generate object name for storage
     */
    private fun generateObjectName(metricId: UUID, date: Instant): String {
        val dateStr = date.atZone(ZoneId.of("UTC")).toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "metrics/$metricId/$dateStr.json.gz"
    }
    
    /**
     * Perform incremental vacuum to reclaim space without blocking
     */
    private suspend fun performIncrementalVacuum() {
        try {
            logger.info { "Starting incremental vacuum" }
            
            // Use VACUUM with minimal impact settings
            metricSampleRepository.performIncrementalVacuum()
            
            logger.info { "Incremental vacuum completed" }
        } catch (e: Exception) {
            logger.error(e) { "Error during vacuum operation" }
        }
    }
    
    /**
     * Query archived data from cold storage
     */
    suspend fun queryArchivedData(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<MetricSample> = flow {
        // Find relevant files in cold storage
        val metadata = coldStorageMetadataRepository.findByMetricIdAndTimeRange(
            metricId, startTime, endTime
        ).toList()
        
        metadata.forEach { meta ->
            // Download and read JSON+GZIP file
            val samples = readJsonGzipFile(meta.storagePath)
            samples.forEach { emit(it) }
        }
    }
    
    /**
     * Read samples from a JSON+GZIP file
     */
    private suspend fun readJsonGzipFile(objectName: String): List<MetricSample> {
        return try {
            // Download file from MinIO
            val inputStream = minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                    .bucket(bucketName)
                    .`object`(objectName)
                    .build()
            )
            
            // Decompress and parse JSON
            val decompressed = java.util.zip.GZIPInputStream(inputStream).use { gzip ->
                gzip.readBytes()
            }
            
            val json = String(decompressed)
            
            // Parse JSON array to MetricSample objects
            @Suppress("UNCHECKED_CAST")
            val rawData = objectMapper.readValue(json, List::class.java) as List<Map<String, Any>>
            
            rawData.map { data ->
                MetricSample(
                    time = Instant.ofEpochMilli((data["timestamp"] as Number).toLong()),
                    metricId = UUID.fromString(data["metric_id"] as String),
                    value = (data["value"] as Number).toDouble(),
                    labelsMap = parseLabelsFromString(data["labels"] as String)
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to read archived file $objectName" }
            emptyList()
        }
    }
    
    /**
     * Parse labels from JSON string
     */
    private fun parseLabelsFromString(labelsJson: String): Map<String, String> {
        return try {
            if (labelsJson.isBlank() || labelsJson == "{}") {
                emptyMap()
            } else {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(labelsJson, Map::class.java) as Map<String, String>
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse labels: $labelsJson" }
            emptyMap()
        }
    }
    
    /**
     * Get archival statistics
     */
    suspend fun getArchivalStats(): Map<String, Any> {
        return mapOf(
            "totalRowsArchived" to totalRowsArchived.get(),
            "totalBytesArchived" to totalBytesArchived.get(),
            "isArchiving" to isArchiving.get()
        )
    }
}
