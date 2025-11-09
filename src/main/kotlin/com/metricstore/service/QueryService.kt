package com.metricstore.service

import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.*
import com.metricstore.repository.MetricSampleRepository
import com.metricstore.repository.AggregatedResult
import com.metricstore.repository.RateResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

@Service
class QueryService(
    private val metricService: MetricService,
    private val metricSampleRepository: MetricSampleRepository
) {
    
    suspend fun queryMetrics(request: MetricQueryRequest): MetricQueryResponse {
        // Validate metric name
        if (request.metricName.isBlank()) {
            throw IllegalArgumentException("Metric name cannot be blank")
        }
        
        logger.debug { "Querying metrics: ${request.metricName} from ${request.startTime} to ${request.endTime}" }
        
        // Get metric metadata
        val metric = metricService.getMetricByName(request.metricName)
            ?: throw NoSuchElementException("Metric not found: ${request.metricName}")
        
        val metricId = metric.id ?: throw IllegalStateException("Metric ${request.metricName} has no id")
        
        // Set default time range if not specified (handles null properly)
        val endTime = request.endTime ?: Instant.now()
        val startTime = request.startTime ?: endTime.minus(Duration.ofHours(24))
        
        // Validate time range
        if (startTime.isAfter(endTime)) {
            throw IllegalArgumentException("Start time must be before end time")
        }
        
        // Validate time range is not too large (max 90 days)
        val maxRange = Duration.ofDays(90)
        if (Duration.between(startTime, endTime) > maxRange) {
            throw IllegalArgumentException("Time range cannot exceed 90 days")
        }
        
        // Validate interval format if provided
        if (!request.interval.isNullOrBlank()) {
            if (!request.interval.matches(Regex("^\\d+[smhd]$"))) {
                throw IllegalArgumentException("Invalid interval format. Use format like '5m', '1h', '1d'")
            }
        }
        
        return when (request.aggregation) {
            null -> queryRawMetrics(metricId, startTime, endTime, request.labels, request.limit)
            AggregationType.RATE -> {
                if (metric.type != MetricType.COUNTER) {
                    throw IllegalArgumentException("Rate calculation is only available for counter metrics")
                }
                queryRateMetrics(metricId, startTime, endTime, request.labels, request.limit)
            }
            AggregationType.P50, AggregationType.P75, AggregationType.P90, 
            AggregationType.P95, AggregationType.P99 -> {
                queryPercentileMetrics(
                    metricId, 
                    startTime, 
                    endTime, 
                    request.aggregation,
                    request.labels
                )
            }
            else -> {
                if (request.interval.isNullOrBlank()) {
                    queryAggregatedMetrics(
                        metricId,
                        startTime,
                        endTime,
                        request.aggregation,
                        request.labels
                    )
                } else {
                    queryTimeBucketedMetrics(
                        metricId,
                        startTime,
                        endTime,
                        request.interval,
                        request.aggregation,
                        request.labels
                    )
                }
            }
        }.let { data ->
            MetricQueryResponse(
                metric = request.metricName,
                data = data,
                aggregation = request.aggregation,
                interval = request.interval,
                totalPoints = data.size
            )
        }
    }
    
    private suspend fun queryRawMetrics(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labels: Map<String, String>,
        limit: Int
    ): List<MetricDataPoint> {
        val samples = if (labels.isEmpty()) {
            metricSampleRepository.findByMetricAndTimeRange(metricId, startTime, endTime)
        } else {
            val labelsJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(labels)
            metricSampleRepository.findByMetricAndTimeRangeAndLabels(
                metricId, 
                startTime, 
                endTime, 
                labelsJson
            )
        }
        
        return samples
            .take(limit)
            .map { sample ->
                MetricDataPoint(
                    timestamp = sample.time,
                    value = sample.value,
                    labels = sample.getLabelsAsMap()
                )
            }
            .toList()
    }
    
    private suspend fun queryRateMetrics(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labels: Map<String, String>,
        limit: Int
    ): List<MetricDataPoint> {
        return metricSampleRepository.calculateRate(metricId, startTime, endTime)
            .take(limit)
            .map { result ->
                MetricDataPoint(
                    timestamp = result.time,
                    value = result.rate,
                    labels = com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        result.labels.asString(),
                        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
                    )
                )
            }
            .toList()
    }
    
    private suspend fun queryPercentileMetrics(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        aggregationType: AggregationType,
        labels: Map<String, String>
    ): List<MetricDataPoint> {
        val percentile = when (aggregationType) {
            AggregationType.P50 -> 0.50
            AggregationType.P75 -> 0.75
            AggregationType.P90 -> 0.90
            AggregationType.P95 -> 0.95
            AggregationType.P99 -> 0.99
            else -> throw IllegalArgumentException("Invalid percentile type: $aggregationType")
        }
        
        val value = metricSampleRepository.calculatePercentile(
            percentile,
            metricId,
            startTime,
            endTime
        ) ?: 0.0
        
        return listOf(
            MetricDataPoint(
                timestamp = endTime,
                value = value,
                labels = labels
            )
        )
    }
    
    private suspend fun queryAggregatedMetrics(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        aggregationType: AggregationType,
        labels: Map<String, String>
    ): List<MetricDataPoint> {
        // For overall aggregation, we'll use 1 large bucket
        val interval = Duration.between(startTime, endTime).toSeconds().toString() + "s"
        
        val result = metricSampleRepository.aggregateByTimeBucket(
            interval,
            metricId,
            startTime,
            endTime
        ).toList()
        
        return result.map { agg ->
            val value = when (aggregationType) {
                AggregationType.SUM -> agg.sumValue
                AggregationType.AVG -> agg.avgValue
                AggregationType.MIN -> agg.minValue
                AggregationType.MAX -> agg.maxValue
                AggregationType.COUNT -> agg.count.toDouble()
                else -> agg.avgValue
            } ?: 0.0
            
            MetricDataPoint(
                timestamp = agg.bucket,
                value = value,
                labels = labels
            )
        }
    }
    
    private suspend fun queryTimeBucketedMetrics(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        interval: String,
        aggregationType: AggregationType,
        labels: Map<String, String>
    ): List<MetricDataPoint> {
        // Convert interval to PostgreSQL format
        val postgresInterval = parseInterval(interval)
        
        logger.debug { "Querying time-bucketed metrics: interval=$postgresInterval, metricId=$metricId, start=$startTime, end=$endTime" }
        
        try {
            // Add timeout and proper Flow collection with limit
            return kotlinx.coroutines.withTimeout(5000) { // 5 second timeout
                val results = if (labels.isNotEmpty()) {
                    // Use label filtering if labels are provided
                    val labelsJson = com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(labels)
                    metricSampleRepository.aggregateByTimeBucketWithLabels(
                        postgresInterval,
                        metricId,
                        startTime,
                        endTime,
                        labelsJson
                    )
                } else {
                    // Use regular aggregation without label filtering
                    metricSampleRepository.aggregateByTimeBucket(
                        postgresInterval,
                        metricId,
                        startTime,
                        endTime
                    )
                }
                
                // Collect with a limit to prevent infinite streams
                results
                    .take(1000) // Limit to 1000 buckets max
                    .map { agg ->
                        val value = when (aggregationType) {
                            AggregationType.SUM -> agg.sumValue
                            AggregationType.AVG -> agg.avgValue
                            AggregationType.MIN -> agg.minValue
                            AggregationType.MAX -> agg.maxValue
                            AggregationType.COUNT -> agg.count.toDouble()
                            else -> agg.avgValue
                        } ?: 0.0
                        
                        MetricDataPoint(
                            timestamp = agg.bucket,
                            value = value,
                            labels = labels
                        )
                    }
                    .toList()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error { "Query timeout for metric $metricId with interval $postgresInterval" }
            throw IllegalStateException("Query timeout: The aggregation query took too long to execute. Try a larger interval or smaller time range.")
        } catch (e: Exception) {
            logger.error(e) { "Error querying time-bucketed metrics" }
            throw e
        }
    }
    
    private fun parseInterval(interval: String): String {
        // Convert interval format from "5m", "1h", "1d" to PostgreSQL interval
        val regex = """(\d+)([smhd])""".toRegex()
        val match = regex.matchEntire(interval)
            ?: throw IllegalArgumentException("Invalid interval format: $interval")
        
        val (amount, unit) = match.destructured
        return when (unit) {
            "s" -> "$amount seconds"
            "m" -> "$amount minutes"
            "h" -> "$amount hours"
            "d" -> "$amount days"
            else -> throw IllegalArgumentException("Invalid interval unit: $unit")
        }
    }
    
    suspend fun exportMetrics(request: ExportRequest): String {
        // Validate export request
        if (request.metricName.isBlank()) {
            throw IllegalArgumentException("Metric name cannot be blank for export")
        }
        
        // Validate time range if both provided
        if (request.startTime != null && request.endTime != null) {
            if (request.startTime.isAfter(request.endTime)) {
                throw IllegalArgumentException("Start time must be before end time")
            }
            
            // Validate export range is not too large (max 30 days for exports)
            val maxExportRange = Duration.ofDays(30)
            if (Duration.between(request.startTime, request.endTime) > maxExportRange) {
                throw IllegalArgumentException("Export time range cannot exceed 30 days")
            }
        }
        
        val queryRequest = MetricQueryRequest(
            metricName = request.metricName,
            startTime = request.startTime,
            endTime = request.endTime,
            labels = request.labels,
            limit = 10000
        )
        
        val response = queryMetrics(queryRequest)
        
        return when (request.format) {
            ExportFormat.JSON -> exportAsJson(response)
            ExportFormat.CSV -> exportAsCsv(response)
            ExportFormat.PROMETHEUS -> exportAsPrometheus(response)
        }
    }
    
    private fun exportAsJson(response: MetricQueryResponse): String {
        return com.fasterxml.jackson.databind.ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(response)
    }
    
    private fun exportAsCsv(response: MetricQueryResponse): String {
        val csv = StringBuilder()
        csv.append("timestamp,metric,value,labels\n")
        
        response.data.forEach { point ->
            csv.append("${point.timestamp},${response.metric},${point.value},\"${point.labels}\"\n")
        }
        
        return csv.toString()
    }
    
    private fun exportAsPrometheus(response: MetricQueryResponse): String {
        val prometheus = StringBuilder()
        
        response.data.forEach { point ->
            val labelsStr = point.labels.entries.joinToString(",") { "${it.key}=\"${it.value}\"" }
            val labelsPart = if (labelsStr.isNotEmpty()) "{$labelsStr}" else ""
            prometheus.append("${response.metric}$labelsPart ${point.value} ${point.timestamp.toEpochMilli()}\n")
        }
        
        return prometheus.toString()
    }
    
    suspend fun getMetricStatistics(
        metricName: String,
        startTime: Instant? = null,
        endTime: Instant? = null
    ): Map<String, Any> {
        // Validate metric name
        if (metricName.isBlank()) {
            throw IllegalArgumentException("Metric name cannot be blank")
        }
        
        // Validate time range if both provided
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw IllegalArgumentException("Start time must be before end time")
        }
        
        val metric = metricService.getMetricByName(metricName)
            ?: throw NoSuchElementException("Metric not found: $metricName")
        
        val metricId = metric.id ?: throw IllegalStateException("Metric $metricName has no id")
        
        val end = endTime ?: Instant.now()
        val start = startTime ?: end.minus(Duration.ofHours(24))
        
        // Get various statistics
        val stats = mutableMapOf<String, Any>()
        
        // Get aggregated stats
        val aggregated = metricSampleRepository.aggregateByTimeBucket(
            Duration.between(start, end).toSeconds().toString() + " seconds",
            metricId,
            start,
            end
        ).toList().firstOrNull()
        
        aggregated?.let {
            stats["avg"] = it.avgValue ?: 0.0
            stats["sum"] = it.sumValue ?: 0.0
            stats["min"] = it.minValue ?: 0.0
            stats["max"] = it.maxValue ?: 0.0
            stats["count"] = it.count
        }
        
        // Get percentiles
        listOf(0.50 to "p50", 0.95 to "p95", 0.99 to "p99").forEach { (percentile, name) ->
            val value = metricSampleRepository.calculatePercentile(
                percentile,
                metricId,
                start,
                end
            )
            stats[name] = value ?: 0.0
        }
        
        return stats
    }
}
