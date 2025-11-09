package com.metricstore.api

import com.metricstore.domain.model.*
import com.metricstore.service.QueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import mu.KotlinLogging
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Query", description = "Metric query and analytics endpoints")
class QueryController(
    private val queryService: QueryService
) {
    
    @GetMapping("/query")
    @Operation(
        summary = "Query metrics", 
        description = "Query metrics with optional aggregation, time range, and label filtering"
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Query executed successfully"),
        ApiResponse(responseCode = "400", description = "Invalid query parameters"),
        ApiResponse(responseCode = "404", description = "Metric not found")
    )
    suspend fun queryMetrics(
        @Parameter(description = "Metric name", required = true)
        @RequestParam metricName: String,
        
        @Parameter(description = "Start time (ISO-8601)")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        startTime: Instant?,
        
        @Parameter(description = "End time (ISO-8601)")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        endTime: Instant?,
        
        @Parameter(description = "Aggregation type (SUM, AVG, MIN, MAX, COUNT, P50, P75, P90, P95, P99, RATE)")
        @RequestParam(required = false)
        aggregation: AggregationType?,
        
        @Parameter(description = "Time bucket interval (e.g., 5m, 1h, 1d)")
        @RequestParam(required = false)
        interval: String?,
        
        @Parameter(description = "Label filters as key=value pairs")
        @RequestParam(required = false)
        labels: Map<String, String>?,
        
        @Parameter(description = "Maximum number of data points to return")
        @RequestParam(defaultValue = "100")
        limit: Int
    ): ResponseEntity<*> {
        val request = MetricQueryRequest(
            metricName = metricName,
            startTime = startTime,
            endTime = endTime,
            aggregation = aggregation,
            interval = interval,
            labels = labels ?: emptyMap(),
            limit = limit
        )
        
        return try {
            val response = queryService.queryMetrics(request)
            ResponseEntity.ok(response) as ResponseEntity<*>
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("error" to "Metric not found")
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf("error" to (e.message ?: "Bad request"))
            )
        }
    }
    
    @PostMapping("/query")
    @Operation(
        summary = "Query metrics with request body", 
        description = "Query metrics using a structured request body for complex queries"
    )
    suspend fun queryMetricsPost(
        @Valid @RequestBody request: MetricQueryRequest
    ): ResponseEntity<*> {
        return try {
            val response = queryService.queryMetrics(request)
            ResponseEntity.ok(response) as ResponseEntity<*>
        } catch (e: NoSuchElementException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("error" to "Metric not found")
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                mapOf("error" to (e.message ?: "Bad request"))
            )
        }
    }
    
    @GetMapping("/query/statistics")
    @Operation(
        summary = "Get metric statistics",
        description = "Get statistical summary for a metric including min, max, avg, percentiles"
    )
    suspend fun getMetricStatistics(
        @Parameter(description = "Metric name", required = true)
        @RequestParam metricName: String,
        
        @Parameter(description = "Start time (ISO-8601)")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        startTime: Instant?,
        
        @Parameter(description = "End time (ISO-8601)")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        endTime: Instant?
    ): ResponseEntity<Map<String, Any>> {
        return try {
            val stats = queryService.getMetricStatistics(metricName, startTime, endTime)
            ResponseEntity.ok(stats)
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
    
    @GetMapping("/export")
    @Operation(
        summary = "Export metrics",
        description = "Export metrics in various formats (JSON, CSV, Prometheus)"
    )
    suspend fun exportMetrics(
        @Parameter(description = "Metric name", required = true)
        @RequestParam metricName: String,
        
        @Parameter(description = "Start time (ISO-8601)", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        startTime: Instant,
        
        @Parameter(description = "End time (ISO-8601)", required = true)
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        endTime: Instant,
        
        @Parameter(description = "Export format")
        @RequestParam(defaultValue = "JSON")
        format: ExportFormat,
        
        @Parameter(description = "Label filters")
        @RequestParam(required = false)
        labels: Map<String, String>?
    ): ResponseEntity<String> {
        val request = ExportRequest(
            metricName = metricName,
            startTime = startTime,
            endTime = endTime,
            format = format,
            labels = labels ?: emptyMap()
        )
        
        return try {
            val data = queryService.exportMetrics(request)
            
            val mediaType = when (format) {
                ExportFormat.CSV -> MediaType.parseMediaType("text/csv")
                ExportFormat.PROMETHEUS -> MediaType.parseMediaType("text/plain")
                else -> MediaType.APPLICATION_JSON
            }
            
            val filename = "${metricName}_${startTime}_${endTime}.${format.name.lowercase()}"
            
            ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
                .body(data)
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
    
    @GetMapping("/query/range")
    @Operation(
        summary = "Range query for metrics",
        description = "Query metrics over a time range with step intervals"
    )
    suspend fun rangeQuery(
        @RequestParam metricName: String,
        @RequestParam 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        start: Instant,
        @RequestParam 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        end: Instant,
        @RequestParam step: String,
        @RequestParam(required = false) labels: Map<String, String>?
    ): ResponseEntity<MetricQueryResponse> {
        val request = MetricQueryRequest(
            metricName = metricName,
            startTime = start,
            endTime = end,
            interval = step,
            aggregation = AggregationType.AVG,
            labels = labels ?: emptyMap(),
            limit = 10000
        )
        
        return try {
            val response = queryService.queryMetrics(request)
            ResponseEntity.ok(response)
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
    
    @GetMapping("/query/instant")
    @Operation(
        summary = "Instant query",
        description = "Get the most recent value for a metric"
    )
    suspend fun instantQuery(
        @RequestParam metricName: String,
        @RequestParam(required = false) 
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        time: Instant?,
        @RequestParam(required = false) labels: Map<String, String>?
    ): ResponseEntity<MetricDataPoint> {
        val queryTime = time ?: Instant.now()
        val request = MetricQueryRequest(
            metricName = metricName,
            startTime = queryTime.minusSeconds(60),
            endTime = queryTime,
            labels = labels ?: emptyMap(),
            limit = 1
        )
        
        return try {
            val response = queryService.queryMetrics(request)
            if (response.data.isNotEmpty()) {
                ResponseEntity.ok(response.data.first())
            } else {
                ResponseEntity.noContent().build()
            }
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
}
