package com.metricstore.api

import com.metricstore.domain.model.MetricDataPoint
import com.metricstore.service.ColdStorageArchivalService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.*

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/archive")
@Tag(name = "Archive", description = "Cold storage archival operations")
class ArchiveController(
    private val archivalService: ColdStorageArchivalService
) {
    @GetMapping("/query")
    @Operation(summary = "Query archived data from cold storage")
    suspend fun queryArchivedData(
        @RequestParam metricId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startTime: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endTime: Instant
    ): Map<String, Any> {
        if (archivalService == null) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Cold storage archival service is not available"
            )
        }
        
        logger.info { "Querying archived data for metric $metricId from $startTime to $endTime" }
        
        return try {
            val samples = archivalService.queryArchivedData(metricId, startTime, endTime)
                .map { sample ->
                    MetricDataPoint(
                        timestamp = sample.time,
                        value = sample.value,
                        labels = sample.getLabelsAsMap()
                    )
                }
                .toList()
            
            mapOf(
                "metricId" to metricId.toString(),
                "startTime" to startTime.toString(),
                "endTime" to endTime.toString(),
                "totalPoints" to samples.size,
                "data" to samples
            )
        } catch (e: Exception) {
            logger.error(e) { "Error querying archived data" }
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to query archived data: ${e.message}"
            )
        }
    }
    
    @GetMapping("/stats")
    @Operation(summary = "Get archival statistics")
    suspend fun getArchivalStats(): Map<String, Any> {
        if (archivalService == null) {
            return mapOf(
                "totalRowsArchived" to 0,
                "totalBytesArchived" to 0,
                "isArchiving" to false,
                "status" to "disabled"
            )
        }
        return archivalService.getArchivalStats()
    }
    
    @PostMapping("/trigger")
    @Operation(summary = "Manually trigger archival job (admin only)")
    fun triggerArchival(): Map<String, String> {
        if (archivalService == null) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Cold storage archival service is not available"
            )
        }
        
        logger.warn { "Manual archival trigger requested" }
        
        return try {
            // Launch in background
            GlobalScope.launch(Dispatchers.IO) {
                archivalService.archiveOldDataJob()
            }
            
            mapOf(
                "status" to "started",
                "message" to "Archival job started in background"
            )
        } catch (e: Exception) {
            logger.error(e) { "Error triggering archival" }
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to trigger archival: ${e.message}"
            )
        }
    }
}
