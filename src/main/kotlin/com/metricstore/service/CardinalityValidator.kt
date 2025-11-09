package com.metricstore.service

import com.metricstore.domain.entity.Metric
import com.metricstore.repository.MetricSampleRepository
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import kotlinx.coroutines.reactive.awaitFirstOrNull
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Service to validate and protect against high cardinality metrics.
 * High cardinality can cause:
 * - Memory explosion
 * - Slow queries
 * - Index bloat
 * - Storage issues
 */
@Service
class CardinalityValidator(
    private val metricSampleRepository: MetricSampleRepository,
    @Value("\${metrics.cardinality.max-series-per-metric:10000}")
    private val maxSeriesPerMetric: Int,
    @Value("\${metrics.cardinality.max-labels-per-metric:10}")
    private val maxLabelsPerMetric: Int,
    @Value("\${metrics.cardinality.max-label-value-length:100}")
    private val maxLabelValueLength: Int,
    @Value("\${metrics.cardinality.warning-threshold:0.8}")
    private val warningThreshold: Double,
    @Value("\${metrics.cardinality.check-window-hours:24}")
    private val checkWindowHours: Long
) {
    // Track cardinality per metric
    private val cardinalityCache = ConcurrentHashMap<UUID, CardinalityInfo>()
    
    // Rate limiting for cardinality checks to avoid overloading the DB
    private val rateLimiter = Bucket.builder()
        .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
        .build()
    
    data class CardinalityInfo(
        val uniqueSeries: Int,
        val lastChecked: Instant,
        val labelCardinality: Map<String, Int>
    )
    
    data class CardinalityValidationResult(
        val isValid: Boolean,
        val currentCardinality: Int,
        val maxAllowed: Int,
        val warnings: List<String>,
        val errors: List<String>
    )
    
    /**
     * Validates if adding new labels would exceed cardinality limits
     */
    suspend fun validateLabelCardinality(
        metric: Metric,
        proposedLabels: Map<String, String>
    ): CardinalityValidationResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        // Check 1: Number of labels
        if (proposedLabels.size > maxLabelsPerMetric) {
            errors.add(
                "Too many labels: ${proposedLabels.size} exceeds maximum of $maxLabelsPerMetric. " +
                "High-cardinality labels should be avoided (e.g., user_id, session_id, request_id)"
            )
        }
        
        // Check 2: Label value length
        proposedLabels.forEach { (key, value) ->
            if (value.length > maxLabelValueLength) {
                errors.add(
                    "Label '$key' value is too long: ${value.length} characters exceeds maximum of $maxLabelValueLength"
                )
            }
            
            // Warn about potentially high-cardinality labels
            if (isHighCardinalityLabel(key)) {
                warnings.add(
                    "Label '$key' appears to be high-cardinality. Consider using a lower cardinality alternative"
                )
            }
        }
        
        // Check 3: Estimate series cardinality
        val currentCardinality = if (rateLimiter.tryConsume(1)) {
            estimateCardinality(metric.id!!)
        } else {
            // Use cached value if rate limited
            cardinalityCache[metric.id]?.uniqueSeries ?: 0
        }
        
        // Check if we're approaching the limit
        if (currentCardinality > maxSeriesPerMetric * warningThreshold) {
            if (currentCardinality >= maxSeriesPerMetric) {
                errors.add(
                    "Metric '${metric.name}' has reached maximum cardinality: $currentCardinality series. " +
                    "Cannot add new label combinations."
                )
            } else {
                warnings.add(
                    "Metric '${metric.name}' is approaching cardinality limit: $currentCardinality of $maxSeriesPerMetric series (${(currentCardinality * 100.0 / maxSeriesPerMetric).toInt()}%)"
                )
            }
        }
        
        return CardinalityValidationResult(
            isValid = errors.isEmpty(),
            currentCardinality = currentCardinality,
            maxAllowed = maxSeriesPerMetric,
            warnings = warnings,
            errors = errors
        )
    }
    
    /**
     * Estimates the cardinality of a metric by counting unique label combinations
     */
    private suspend fun estimateCardinality(metricId: UUID): Int {
        // Check cache first
        val cached = cardinalityCache[metricId]
        if (cached != null && cached.lastChecked.isAfter(Instant.now().minusSeconds(3600))) {
            return cached.uniqueSeries
        }
        
        try {
            // Query unique label combinations in the last window
            val startTime = Instant.now().minusSeconds(checkWindowHours * 3600)
            val query = """
                SELECT COUNT(DISTINCT labels) as cardinality
                FROM metric_samples
                WHERE metric_id = $1 
                AND time > $2
            """.trimIndent()
            
            // This is a simplified estimation - in production you might want a more sophisticated approach
            val cardinality = metricSampleRepository.countDistinctLabelCombinations(
                metricId,
                startTime
            ).awaitFirstOrNull() ?: 0
            
            // Update cache
            cardinalityCache[metricId] = CardinalityInfo(
                uniqueSeries = cardinality,
                lastChecked = Instant.now(),
                labelCardinality = emptyMap() // Could be enhanced to track per-label cardinality
            )
            
            logger.debug { "Metric $metricId has cardinality: $cardinality" }
            return cardinality
        } catch (e: Exception) {
            logger.error(e) { "Failed to estimate cardinality for metric $metricId" }
            return cached?.uniqueSeries ?: 0
        }
    }
    
    /**
     * Checks if a label key is likely to be high cardinality
     */
    private fun isHighCardinalityLabel(labelKey: String): Boolean {
        val highCardinalityPatterns = listOf(
            "id", "uuid", "guid",
            "session", "request", "transaction",
            "user", "customer", "account",
            "email", "username",
            "ip", "address",
            "timestamp", "datetime",
            "random", "nonce", "token"
        )
        
        val lowerKey = labelKey.toLowerCase()
        return highCardinalityPatterns.any { pattern ->
            lowerKey.contains(pattern)
        }
    }
    
    /**
     * Get cardinality statistics for a metric
     */
    suspend fun getCardinalityStats(metricId: UUID): Map<String, Any> {
        val cardinality = estimateCardinality(metricId)
        val cached = cardinalityCache[metricId]
        
        return mapOf(
            "currentCardinality" to cardinality,
            "maxAllowed" to maxSeriesPerMetric,
            "utilizationPercent" to (cardinality * 100.0 / maxSeriesPerMetric),
            "lastChecked" to (cached?.lastChecked ?: Instant.now()),
            "isWarning" to (cardinality > maxSeriesPerMetric * warningThreshold),
            "isCritical" to (cardinality >= maxSeriesPerMetric)
        )
    }
    
    /**
     * Cleanup old cache entries
     */
    fun cleanupCache() {
        val cutoff = Instant.now().minusSeconds(7200) // 2 hours
        cardinalityCache.entries.removeIf { entry ->
            entry.value.lastChecked.isBefore(cutoff)
        }
    }
}
