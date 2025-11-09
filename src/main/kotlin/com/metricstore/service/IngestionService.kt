package com.metricstore.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.metricstore.config.MetricStoreProperties
import com.metricstore.domain.entity.MetricSample
import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.IngestionError
import com.metricstore.domain.model.MetricIngestionRequest
import com.metricstore.domain.model.MetricIngestionResponse
import com.metricstore.domain.model.MetricSampleRequest
import com.metricstore.repository.MetricSampleRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger {}

@Service
class IngestionService(
    private val metricService: MetricService,
    private val metricSampleRepository: MetricSampleRepository,
    private val properties: MetricStoreProperties,
    private val objectMapper: ObjectMapper,
    private val cardinalityValidator: CardinalityValidator
) {
    private val totalAccepted = AtomicLong(0)
    private val totalRejected = AtomicLong(0)
    private val buffer = ConcurrentLinkedQueue<MetricSample>()
    
    suspend fun ingestMetrics(request: MetricIngestionRequest): MetricIngestionResponse {
        // Validate batch size
        if (request.metrics.isEmpty()) {
            throw IllegalArgumentException("Cannot ingest empty batch")
        }
        if (request.metrics.size > properties.ingestion.buffer.maxSize) {
            throw IllegalArgumentException("Batch size ${request.metrics.size} exceeds maximum ${properties.ingestion.buffer.maxSize}")
        }
        
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<IngestionError>()
        val accepted = AtomicLong(0)
        val rejected = AtomicLong(0)
        
        coroutineScope {
            request.metrics.mapIndexed { index, sampleRequest ->
                async {
                    try {
                        ingestSingleMetric(sampleRequest)
                        accepted.incrementAndGet()
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to ingest metric: ${sampleRequest.name}" }
                        errors.add(IngestionError(index, sampleRequest.name, e.message ?: "Unknown error"))
                        rejected.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        
        totalAccepted.addAndGet(accepted.get())
        totalRejected.addAndGet(rejected.get())
        
        val duration = System.currentTimeMillis() - startTime
        
        return MetricIngestionResponse(
            accepted = accepted.get().toInt(),
            rejected = rejected.get().toInt(),
            errors = errors,
            durationMs = duration
        )
    }
    
    private suspend fun ingestSingleMetric(request: MetricSampleRequest) {
        // Validate metric name
        if (request.name.isBlank()) {
            throw IllegalArgumentException("Metric name cannot be blank")
        }
        if (request.name.length > 255) {
            throw IllegalArgumentException("Metric name exceeds maximum length of 255 characters")
        }
        if (!request.name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.-]*$"))) {
            throw IllegalArgumentException("Invalid metric name format: ${request.name}")
        }
        
        // Validate value
        if (request.value.isNaN() || request.value.isInfinite()) {
            throw IllegalArgumentException("Invalid metric value: ${request.value}")
        }
        
        // Validate timestamp (not too far in future or past)
        val now = Instant.now()
        val maxFuture = now.plusSeconds(300) // 5 minutes in future
        val maxPast = now.minus(java.time.Duration.ofDays(365)) // 1 year in past
        if (request.timestamp.isAfter(maxFuture)) {
            throw IllegalArgumentException("Timestamp ${request.timestamp} is too far in the future (max 5 minutes)")
        }
        if (request.timestamp.isBefore(maxPast)) {
            throw IllegalArgumentException("Timestamp ${request.timestamp} is too far in the past (max 1 year)")
        }
        
        // Get or create metric
        val metric = getOrAutoRegisterMetric(request.name, request.type ?: MetricType.GAUGE)
        
        // Ensure metric has an id
        val metricId = metric.id ?: throw IllegalStateException("Metric ${request.name} has no id")
        
        // Validate labels against registered schema
        val registeredLabels = metricService.getMetricLabels(metricId)
        validateLabels(request.name, request.labels, registeredLabels)
        
        // Validate cardinality to prevent metric explosion
        val cardinalityResult = cardinalityValidator.validateLabelCardinality(metric, request.labels)
        if (!cardinalityResult.isValid) {
            val errorMsg = cardinalityResult.errors.joinToString("; ")
            logger.error { "Cardinality validation failed for ${request.name}: $errorMsg" }
            throw IllegalArgumentException("Cardinality limit exceeded: $errorMsg")
        }
        if (cardinalityResult.warnings.isNotEmpty()) {
            logger.warn { "Cardinality warnings for ${request.name}: ${cardinalityResult.warnings.joinToString("; ")}" }
        }
        
        // Create sample with proper JSON encoding for labels
        val sample = MetricSample(
            time = request.timestamp,
            metricId = metricId,
            value = request.value,
            labelsMap = request.labels
        )
        
        // Add to buffer
        buffer.offer(sample)
        
        // Check if buffer should be flushed
        if (buffer.size >= properties.ingestion.buffer.maxSize) {
            flushBuffer()
        }
    }
    
    private suspend fun getOrAutoRegisterMetric(name: String, type: MetricType) =
        metricService.getMetricByName(name) ?: metricService.getOrCreateMetric(name, type)
    
    @Scheduled(fixedDelayString = "\${metric-store.ingestion.buffer.flush-interval-ms:5000}")
    fun scheduledFlush() {
        runBlocking {
            flushBuffer()
        }
    }
    
    suspend fun flushBuffer() {
        val samples = mutableListOf<MetricSample>()
        
        // Drain buffer up to batch size
        while (samples.size < properties.ingestion.buffer.batchSize && !buffer.isEmpty()) {
            buffer.poll()?.let { samples.add(it) }
        }
        
        if (samples.isNotEmpty()) {
            try {
                val duration = measureTimeMillis {
                    samples.forEach { sample ->
                        metricSampleRepository.upsert(
                            sample.time,
                            sample.metricId,
                            sample.value,
                            sample.labels.asString()
                        )
                    }
                }
                logger.info { "Flushed ${samples.size} samples to database in ${duration}ms" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to flush buffer" }
                // Re-add samples to buffer on failure
                samples.forEach { buffer.offer(it) }
            }
        }
    }
    
    private fun validateLabels(metricName: String, providedLabels: Map<String, String>, registeredLabels: List<String>) {
        // Validate individual label keys and values
        providedLabels.forEach { (key, value) ->
            if (key.isBlank()) {
                throw IllegalArgumentException("Label key cannot be blank for metric '$metricName'")
            }
            if (key.length > 100) {
                throw IllegalArgumentException("Label key '$key' exceeds maximum length of 100 characters")
            }
            if (!key.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
                throw IllegalArgumentException("Invalid label key format: '$key'")
            }
            if (value.isBlank()) {
                throw IllegalArgumentException("Label value for key '$key' cannot be blank")
            }
            if (value.length > 500) {
                throw IllegalArgumentException("Label value for key '$key' exceeds maximum length of 500 characters")
            }
        }
        
        val providedLabelKeys = providedLabels.keys.toSet()
        val registeredLabelKeys = registeredLabels.toSet()
        
        // Check for missing required labels
        val missingLabels = registeredLabelKeys - providedLabelKeys
        if (missingLabels.isNotEmpty()) {
            throw IllegalArgumentException(
                "Missing required labels for metric '$metricName': ${missingLabels.joinToString(", ")}. " +
                "Expected labels: [${registeredLabels.joinToString(", ")}]"
            )
        }
        
        // Check for extra labels not in schema
        val extraLabels = providedLabelKeys - registeredLabelKeys
        if (extraLabels.isNotEmpty()) {
            throw IllegalArgumentException(
                "Extra labels not allowed for metric '$metricName': ${extraLabels.joinToString(", ")}. " +
                "Expected labels: [${registeredLabels.joinToString(", ")}]"
            )
        }
    }
    
    fun getIngestionStats(): Map<String, Any> {
        return mapOf(
            "totalAccepted" to totalAccepted.get(),
            "totalRejected" to totalRejected.get(),
            "bufferSize" to buffer.size,
            "bufferCapacity" to properties.ingestion.buffer.maxSize
        )
    }
}
