package com.metricstore.domain.model

import com.metricstore.domain.entity.MetricType
import jakarta.validation.constraints.*
import java.time.Instant
import java.util.*

data class MetricRegistrationRequest(
    @field:NotBlank(message = "Metric name is required")
    @field:Size(max = 255, message = "Metric name must be less than 255 characters")
    @field:Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_.-]*$", message = "Invalid metric name format")
    val name: String,
    
    @field:NotNull(message = "Metric type is required")
    val type: MetricType,
    
    @field:Size(max = 1000, message = "Description must be less than 1000 characters")
    val description: String? = null,
    
    @field:Size(max = 100, message = "Unit must be less than 100 characters")
    val unit: String? = null,
    
    val labels: List<String> = emptyList(),
    
    @field:Min(1, message = "Retention days must be at least 1")
    @field:Max(1825, message = "Retention days cannot exceed 5 years (1825 days)")
    val retentionDays: Int? = 30
)

data class MetricResponse(
    val id: UUID,
    val name: String,
    val type: MetricType,
    val description: String?,
    val unit: String?,
    val labels: List<String>,
    val retentionDays: Int,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class MetricIngestionRequest(
    @field:NotEmpty(message = "Metrics list cannot be empty")
    @field:Size(max = 10000, message = "Cannot ingest more than 10000 metrics at once")
    val metrics: List<MetricSampleRequest>
)

data class MetricSampleRequest(
    @field:NotBlank(message = "Metric name is required")
    val name: String,
    
    @field:NotNull(message = "Value is required")
    val value: Double,
    
    val timestamp: Instant = Instant.now(),
    
    val labels: Map<String, String> = emptyMap(),
    
    val type: MetricType? = null // Optional, for auto-registration
)

data class MetricIngestionResponse(
    val accepted: Int,
    val rejected: Int,
    val errors: List<IngestionError> = emptyList(),
    val durationMs: Long? = null
)

data class IngestionError(
    val index: Int,
    val metricName: String,
    val error: String
)

data class MetricQueryRequest(
    @field:NotBlank(message = "Metric name is required")
    val metricName: String,
    
    val startTime: Instant?,
    val endTime: Instant?,
    
    val labels: Map<String, String> = emptyMap(),
    
    val aggregation: AggregationType? = null,
    
    @field:Pattern(regexp = "^(\\d+[smhd])?$", message = "Invalid interval format")
    val interval: String? = null,
    
    @field:Min(1, message = "Limit must be at least 1")
    @field:Max(10000, message = "Limit cannot exceed 10000")
    val limit: Int = 100
)

enum class AggregationType {
    SUM, AVG, MIN, MAX, COUNT,
    P50, P75, P90, P95, P99,
    RATE // For counter metrics
}

data class MetricQueryResponse(
    val metric: String,
    val data: List<MetricDataPoint>,
    val aggregation: AggregationType?,
    val interval: String?,
    val totalPoints: Int
)

data class MetricDataPoint(
    val timestamp: Instant,
    val value: Double,
    val labels: Map<String, String> = emptyMap()
)

data class AggregatedMetricData(
    val bucket: Instant,
    val avgValue: Double?,
    val sumValue: Double?,
    val minValue: Double?,
    val maxValue: Double?,
    val count: Long
)

data class ExportRequest(
    val metricName: String,
    val startTime: Instant,
    val endTime: Instant,
    val format: ExportFormat = ExportFormat.JSON,
    val labels: Map<String, String> = emptyMap()
)

enum class ExportFormat {
    JSON, CSV, PROMETHEUS
}

data class HealthStatus(
    val status: String,
    val database: ComponentHealth,
    val s3: ComponentHealth,
    val ingestionBuffer: BufferHealth
)

data class ComponentHealth(
    val status: String,
    val latencyMs: Long? = null,
    val error: String? = null
)

data class BufferHealth(
    val status: String,
    val currentSize: Int,
    val maxSize: Int,
    val utilizationPercent: Double,
    val lastFlushTime: Instant?
)
