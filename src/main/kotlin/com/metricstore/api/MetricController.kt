package com.metricstore.api

import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.MetricRegistrationRequest
import com.metricstore.domain.model.MetricResponse
import com.metricstore.service.MetricService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Metric management endpoints")
class MetricController(
    private val metricService: MetricService
) {
    
    @PostMapping("/register")
    @Operation(summary = "Register a new metric", description = "Registers a new metric with metadata and label schema")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Metric registered successfully"),
        ApiResponse(responseCode = "400", description = "Invalid metric data"),
        ApiResponse(responseCode = "409", description = "Metric already exists")
    )
    suspend fun registerMetric(
        @Valid @RequestBody request: MetricRegistrationRequest
    ): ResponseEntity<MetricResponse> {
        return try {
            val response = metricService.registerMetric(request)
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("already exists") == true) {
                ResponseEntity.status(HttpStatus.CONFLICT).build()
            } else {
                ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
            }
        }
    }
    
    @GetMapping
    @Operation(summary = "List metrics", description = "Retrieves a list of metrics with optional filtering")
    suspend fun listMetrics(
        @Parameter(description = "Filter by metric type")
        @RequestParam(required = false) type: MetricType?,
        
        @Parameter(description = "Search by metric name")
        @RequestParam(required = false) search: String?,
        
        @Parameter(description = "Limit")
        @RequestParam(defaultValue = "20") limit: Int,
        
        @Parameter(description = "Offset")
        @RequestParam(defaultValue = "0") offset: Int
    ): List<MetricResponse> {
        return metricService.listMetrics(type, search, limit, offset).toList()
    }
    
    @GetMapping("/{id}")
    @Operation(summary = "Get metric by ID", description = "Retrieves metric details by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Metric found"),
        ApiResponse(responseCode = "404", description = "Metric not found")
    )
    suspend fun getMetricById(
        @PathVariable id: UUID
    ): ResponseEntity<MetricResponse> {
        val metric = metricService.getMetricById(id)
            ?: return ResponseEntity.notFound().build()
        
        val labels = metricService.getMetricLabels(id)
        return ResponseEntity.ok(metric.toResponse(labels))
    }
    
    @GetMapping("/by-name/{name}")
    @Operation(summary = "Get metric by name", description = "Retrieves metric details by name")
    suspend fun getMetricByName(
        @PathVariable name: String
    ): ResponseEntity<MetricResponse> {
        val metric = metricService.getMetricByName(name)
            ?: return ResponseEntity.notFound().build()
        
        val metricId = metric.id ?: throw IllegalStateException("Metric has no id")
        val labels = metricService.getMetricLabels(metricId)
        return ResponseEntity.ok(metric.toResponse(labels))
    }
    
    @PatchMapping("/{id}")
    @Operation(summary = "Update metric", description = "Updates metric retention settings or status")
    suspend fun updateMetric(
        @PathVariable id: UUID,
        @RequestParam(required = false) retentionDays: Int?,
        @RequestParam(required = false) isActive: Boolean?
    ): ResponseEntity<MetricResponse> {
        return try {
            val response = metricService.updateMetric(id, retentionDays, isActive)
            ResponseEntity.ok(response)
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }
    
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete metric", description = "Deactivates a metric (soft delete)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteMetric(@PathVariable id: UUID) {
        metricService.deleteMetric(id)
    }
    
    private fun com.metricstore.domain.entity.Metric.toResponse(labels: List<String> = emptyList()) = MetricResponse(
        id = id!!,
        name = name,
        type = type,
        description = description,
        unit = unit,
        labels = labels,
        retentionDays = retentionDays,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
