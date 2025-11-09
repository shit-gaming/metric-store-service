package com.metricstore.api

import com.metricstore.domain.model.MetricIngestionRequest
import com.metricstore.domain.model.MetricIngestionResponse
import com.metricstore.domain.model.MetricSampleRequest
import com.metricstore.service.IngestionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Ingestion", description = "Metric data ingestion endpoints")
class IngestionController(
    private val ingestionService: IngestionService
) {
    
    @PostMapping("/ingest")
    @Operation(summary = "Ingest metrics", description = "Ingests a batch of metric samples")
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Metrics accepted for processing"),
        ApiResponse(responseCode = "400", description = "Invalid metric data"),
        ApiResponse(responseCode = "413", description = "Batch size exceeds limit")
    )
    suspend fun ingestMetrics(
        @Valid @RequestBody request: MetricIngestionRequest
    ): ResponseEntity<MetricIngestionResponse> {
        logger.debug { "Received ingestion request with ${request.metrics.size} metrics" }
        
        val response = ingestionService.ingestMetrics(request)
        
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(response)
    }
    
    @PostMapping("/ingest/batch", consumes = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Batch ingest metrics (NDJSON)", 
        description = "Ingests metrics in NDJSON format for high-throughput scenarios"
    )
    suspend fun batchIngestNdjson(
        @RequestBody metrics: Flow<MetricSampleRequest>
    ): ResponseEntity<MetricIngestionResponse> {
        val samples = metrics.toList()
        val request = MetricIngestionRequest(metrics = samples)
        val response = ingestionService.ingestMetrics(request)
        
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(response)
    }
    
    @PostMapping("/ingest/single")
    @Operation(summary = "Ingest single metric", description = "Ingests a single metric sample")
    suspend fun ingestSingleMetric(
        @Valid @RequestBody sample: MetricSampleRequest
    ): ResponseEntity<MetricIngestionResponse> {
        val request = MetricIngestionRequest(metrics = listOf(sample))
        val response = ingestionService.ingestMetrics(request)
        
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(response)
    }
    
    @GetMapping("/ingest/stats")
    @Operation(summary = "Get ingestion statistics", description = "Retrieves current ingestion statistics")
    fun getIngestionStats(): ResponseEntity<Map<String, Any>> {
        val stats = ingestionService.getIngestionStats()
        return ResponseEntity.ok(stats)
    }
}
