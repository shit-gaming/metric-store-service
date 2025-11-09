package com.metricstore.api

import com.metricstore.domain.model.BufferHealth
import com.metricstore.domain.model.ComponentHealth
import com.metricstore.domain.model.HealthStatus
import com.metricstore.service.IngestionService
import io.minio.BucketExistsArgs
import io.minio.MinioClient
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactive.awaitFirst
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Health check endpoints")
class HealthController(
    private val databaseClient: DatabaseClient,
    private val ingestionService: IngestionService,
    @Value("\${metric-store.storage.cold-tier.s3.endpoint:http://localhost:9000}")
    private val s3Endpoint: String,
    @Value("\${metric-store.storage.cold-tier.s3.access-key:minioadmin}")
    private val s3AccessKey: String,
    @Value("\${metric-store.storage.cold-tier.s3.secret-key:minioadmin}")
    private val s3SecretKey: String,
    @Value("\${metric-store.storage.cold-tier.s3.bucket-name:cold-storage}")
    private val s3BucketName: String,
    @Value("\${metric-store.ingestion.buffer.max-size:10000}")
    private val bufferMaxSize: Int
) {
    
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Get health status of all components")
    suspend fun health(): ResponseEntity<HealthStatus> {
        val databaseHealth = checkDatabaseHealth()
        val s3Health = checkS3Health()
        val bufferHealth = checkBufferHealth()
        
        val overallStatus = when {
            databaseHealth.status == "DOWN" -> "DOWN"
            s3Health.status == "DOWN" -> "DEGRADED"
            bufferHealth.status == "DOWN" -> "DEGRADED"
            else -> "UP"
        }
        
        val status = HealthStatus(
            status = overallStatus,
            database = databaseHealth,
            s3 = s3Health,
            ingestionBuffer = bufferHealth
        )
        
        return ResponseEntity.ok(status)
    }
    
    @GetMapping("/health/live")
    @Operation(summary = "Liveness probe", description = "Check if service is alive")
    fun liveness(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "alive"))
    }
    
    @GetMapping("/health/ready")
    @Operation(summary = "Readiness probe", description = "Check if service is ready to handle requests")
    suspend fun readiness(): ResponseEntity<Map<String, Any>> {
        val databaseHealth = checkDatabaseHealth()
        val isReady = databaseHealth.status == "UP"
        
        return if (isReady) {
            ResponseEntity.ok(mapOf("status" to "ready", "database" to databaseHealth.status))
        } else {
            ResponseEntity.status(503).body(mapOf("status" to "not ready", "database" to databaseHealth.status))
        }
    }
    
    private suspend fun checkDatabaseHealth(): ComponentHealth {
        return try {
            val latency = measureTimeMillis {
                databaseClient.sql("SELECT 1")
                    .fetch()
                    .first()
                    .awaitFirst()
            }
            ComponentHealth(status = "UP", latencyMs = latency)
        } catch (e: Exception) {
            logger.error(e) { "Database health check failed" }
            ComponentHealth(status = "DOWN", error = e.message)
        }
    }
    
    private fun checkS3Health(): ComponentHealth {
        return try {
            val minioClient = MinioClient.builder()
                .endpoint(s3Endpoint)
                .credentials(s3AccessKey, s3SecretKey)
                .build()
            
            val latency = measureTimeMillis {
                minioClient.bucketExists(
                    BucketExistsArgs.builder()
                        .bucket(s3BucketName)
                        .build()
                )
            }
            
            ComponentHealth(status = "UP", latencyMs = latency)
        } catch (e: Exception) {
            logger.error(e) { "S3 health check failed" }
            ComponentHealth(status = "DOWN", error = e.message)
        }
    }
    
    private fun checkBufferHealth(): BufferHealth {
        val stats = ingestionService.getIngestionStats()
        val currentSize = stats["bufferSize"] as Int
        val utilization = (currentSize.toDouble() / bufferMaxSize) * 100
        
        val status = when {
            utilization > 90 -> "CRITICAL"
            utilization > 70 -> "WARNING"
            else -> "UP"
        }
        
        return BufferHealth(
            status = status,
            currentSize = currentSize,
            maxSize = bufferMaxSize,
            utilizationPercent = utilization,
            lastFlushTime = null // We can track this in IngestionService if needed
        )
    }
}
