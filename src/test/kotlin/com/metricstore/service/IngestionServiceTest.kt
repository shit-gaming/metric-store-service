package com.metricstore.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.metricstore.config.MetricStoreProperties
import com.metricstore.domain.entity.Metric
import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.MetricIngestionRequest
import com.metricstore.domain.model.MetricSampleRequest
import com.metricstore.repository.MetricSampleRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class IngestionServiceTest {

    private val metricService = mockk<MetricService>()
    private val metricSampleRepository = mockk<MetricSampleRepository>()
    private val properties = mockk<MetricStoreProperties>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val cardinalityValidator = mockk<CardinalityValidator>()
    
    private lateinit var ingestionService: IngestionService
    
    @BeforeEach
    fun setup() {
        // Mock properties
        every { properties.ingestion.buffer.maxSize } returns 10000
        every { properties.ingestion.buffer.flushIntervalMs } returns 5000L
        every { properties.ingestion.buffer.batchSize } returns 1000
        
        ingestionService = IngestionService(
            metricService,
            metricSampleRepository,
            properties,
            objectMapper,
            cardinalityValidator
        )
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `ingestMetrics should accept valid metrics`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = Metric(
            id = metricId,
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now(),
                    labels = mapOf("env" to "test"),
                    type = MetricType.GAUGE
                )
            )
        )
        
        val cardinalityResult = CardinalityValidator.CardinalityValidationResult(
            isValid = true,
            currentCardinality = 100,
            maxAllowed = 10000,
            warnings = emptyList(),
            errors = emptyList()
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { metricService.getOrCreateMetric("test_metric", MetricType.GAUGE) } returns metric
        coEvery { metricService.getMetricLabels(metricId) } returns listOf("env")
        coEvery { cardinalityValidator.validateLabelCardinality(metric, any()) } returns cardinalityResult
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(1, response.accepted)
        assertEquals(0, response.rejected)
        assertTrue(response.errors.isEmpty())
    }
    
    @Test
    fun `ingestMetrics should reject empty batch`() = runBlocking {
        // Given
        val request = MetricIngestionRequest(metrics = emptyList())
        
        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            runBlocking {
                ingestionService.ingestMetrics(request)
            }
        }
        assertTrue(exception.message?.contains("empty batch") ?: false)
    }
    
    @Test
    fun `ingestMetrics should handle batch with multiple metrics`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = Metric(
            id = metricId,
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val metrics = (1..10).map { i ->
            MetricSampleRequest(
                name = "test_metric",
                value = i * 10.0,
                timestamp = Instant.now().minusSeconds(i * 60L),
                labels = mapOf("env" to "test"),
                type = MetricType.GAUGE
            )
        }
        
        val request = MetricIngestionRequest(metrics = metrics)
        
        val cardinalityResult = CardinalityValidator.CardinalityValidationResult(
            isValid = true,
            currentCardinality = 100,
            maxAllowed = 10000,
            warnings = emptyList(),
            errors = emptyList()
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { metricService.getOrCreateMetric("test_metric", MetricType.GAUGE) } returns metric
        coEvery { metricService.getMetricLabels(metricId) } returns listOf("env")
        coEvery { cardinalityValidator.validateLabelCardinality(metric, any()) } returns cardinalityResult
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(10, response.accepted)
        assertEquals(0, response.rejected)
    }
    
    @Test
    fun `ingestMetrics should handle mixed success and failure`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = Metric(
            id = metricId,
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now(),
                    labels = mapOf("env" to "test"),
                    type = MetricType.GAUGE
                ),
                MetricSampleRequest(
                    name = "test_metric",
                    value = Double.NaN, // Invalid value
                    timestamp = Instant.now(),
                    labels = mapOf("env" to "test"),
                    type = MetricType.GAUGE
                ),
                MetricSampleRequest(
                    name = "test_metric",
                    value = 200.0,
                    timestamp = Instant.now().plusSeconds(600), // Too far in future
                    labels = mapOf("env" to "test"),
                    type = MetricType.GAUGE
                )
            )
        )
        
        val cardinalityResult = CardinalityValidator.CardinalityValidationResult(
            isValid = true,
            currentCardinality = 100,
            maxAllowed = 10000,
            warnings = emptyList(),
            errors = emptyList()
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { metricService.getOrCreateMetric("test_metric", MetricType.GAUGE) } returns metric
        coEvery { metricService.getMetricLabels(metricId) } returns listOf("env")
        coEvery { cardinalityValidator.validateLabelCardinality(metric, any()) } returns cardinalityResult
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertTrue(response.accepted >= 1)
        assertTrue(response.rejected >= 1) // At least one should be rejected
        assertFalse(response.errors.isEmpty())
    }
    
    @Test
    fun `ingestMetrics should validate metric name format`() = runBlocking {
        // Given
        val invalidNames = listOf(
            "123-invalid", // Starts with number
            "invalid name", // Contains space
            "invalid@metric", // Contains special char
            "", // Empty
            "a".repeat(256) // Too long
        )
        
        val validCardinalityResult = CardinalityValidator.CardinalityValidationResult(
            isValid = true,
            currentCardinality = 100,
            maxAllowed = 10000,
            warnings = emptyList(),
            errors = emptyList()
        )
        
        coEvery { metricService.getMetricByName(any()) } returns null
        coEvery { metricService.getOrCreateMetric(any(), any()) } returns mockk(relaxed = true)
        coEvery { metricService.getMetricLabels(any()) } returns emptyList()
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns validCardinalityResult
        
        invalidNames.forEach { invalidName ->
            val request = MetricIngestionRequest(
                metrics = listOf(
                    MetricSampleRequest(
                        name = invalidName,
                        value = 100.0,
                        timestamp = Instant.now(),
                        labels = emptyMap(),
                        type = MetricType.GAUGE
                    )
                )
            )
            
            // When
            val response = ingestionService.ingestMetrics(request)
            
            // Then
            assertEquals(0, response.accepted, "Should reject invalid name: $invalidName")
            assertEquals(1, response.rejected)
        }
    }
    
    @Test
    fun `ingestMetrics should validate timestamp not too far in future`() = runBlocking {
        // Given
        val metric = mockk<Metric>(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now().plusSeconds(600), // 10 minutes in future
                    labels = emptyMap(),
                    type = MetricType.GAUGE
                )
            )
        )
        
        coEvery { metricService.getMetricByName(any()) } returns metric
        coEvery { metricService.getOrCreateMetric(any(), any()) } returns metric
        coEvery { metricService.getMetricLabels(any()) } returns emptyList()
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns 
            CardinalityValidator.CardinalityValidationResult(true, 0, 10000, emptyList(), emptyList())
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(0, response.accepted)
        assertEquals(1, response.rejected)
        assertTrue(response.errors.isNotEmpty())
        assertTrue(response.errors[0].error.contains("future"))
    }
    
    @Test
    fun `ingestMetrics should validate timestamp not too far in past`() = runBlocking {
        // Given
        val metric = mockk<Metric>(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now().minusSeconds(366 * 24 * 3600), // More than 1 year
                    labels = emptyMap(),
                    type = MetricType.GAUGE
                )
            )
        )
        
        coEvery { metricService.getMetricByName(any()) } returns metric
        coEvery { metricService.getOrCreateMetric(any(), any()) } returns metric
        coEvery { metricService.getMetricLabels(any()) } returns emptyList()
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns 
            CardinalityValidator.CardinalityValidationResult(true, 0, 10000, emptyList(), emptyList())
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(0, response.accepted)
        assertEquals(1, response.rejected)
        assertTrue(response.errors.isNotEmpty())
        assertTrue(response.errors[0].error.contains("past"))
    }
    
    @Test
    fun `ingestMetrics should reject NaN values`() = runBlocking {
        // Given
        val metric = mockk<Metric>(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = Double.NaN,
                    timestamp = Instant.now(),
                    labels = emptyMap(),
                    type = MetricType.GAUGE
                )
            )
        )
        
        coEvery { metricService.getMetricByName(any()) } returns metric
        coEvery { metricService.getOrCreateMetric(any(), any()) } returns metric
        coEvery { metricService.getMetricLabels(any()) } returns emptyList()
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns 
            CardinalityValidator.CardinalityValidationResult(true, 0, 10000, emptyList(), emptyList())
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(0, response.accepted)
        assertEquals(1, response.rejected)
        assertTrue(response.errors.isNotEmpty())
    }
    
    @Test
    fun `ingestMetrics should reject infinite values`() = runBlocking {
        // Given
        val metric = mockk<Metric>(relaxed = true) {
            every { id } returns UUID.randomUUID()
        }
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = Double.POSITIVE_INFINITY,
                    timestamp = Instant.now(),
                    labels = emptyMap(),
                    type = MetricType.GAUGE
                ),
                MetricSampleRequest(
                    name = "test_metric",
                    value = Double.NEGATIVE_INFINITY,
                    timestamp = Instant.now(),
                    labels = emptyMap(),
                    type = MetricType.GAUGE
                )
            )
        )
        
        coEvery { metricService.getMetricByName(any()) } returns metric
        coEvery { metricService.getOrCreateMetric(any(), any()) } returns metric
        coEvery { metricService.getMetricLabels(any()) } returns emptyList()
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns 
            CardinalityValidator.CardinalityValidationResult(true, 0, 10000, emptyList(), emptyList())
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(0, response.accepted)
        assertEquals(2, response.rejected)
    }
    
    @Test
    fun `ingestMetrics should handle cardinality rejection`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = Metric(
            id = metricId,
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now(),
                    labels = mapOf("user_id" to "user123"),
                    type = MetricType.GAUGE
                )
            )
        )
        
        val cardinalityResult = CardinalityValidator.CardinalityValidationResult(
            isValid = false,
            currentCardinality = 10000,
            maxAllowed = 10000,
            warnings = emptyList(),
            errors = listOf("Cardinality limit exceeded")
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { metricService.getOrCreateMetric("test_metric", MetricType.GAUGE) } returns metric
        coEvery { metricService.getMetricLabels(metricId) } returns listOf("user_id")
        coEvery { cardinalityValidator.validateLabelCardinality(metric, any()) } returns cardinalityResult
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(0, response.accepted)
        assertEquals(1, response.rejected)
        assertTrue(response.errors.isNotEmpty())
        assertTrue(response.errors[0].error.contains("Cardinality"))
    }
    
    @Test
    fun `ingestMetrics should log cardinality warnings`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = Metric(
            id = metricId,
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now(),
                    labels = mapOf("user_id" to "user123"),
                    type = MetricType.GAUGE
                )
            )
        )
        
        val cardinalityResult = CardinalityValidator.CardinalityValidationResult(
            isValid = true,
            currentCardinality = 8500,
            maxAllowed = 10000,
            warnings = listOf("Approaching cardinality limit: 8500/10000"),
            errors = emptyList()
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { metricService.getOrCreateMetric("test_metric", MetricType.GAUGE) } returns metric
        coEvery { metricService.getMetricLabels(metricId) } returns listOf("user_id")
        coEvery { cardinalityValidator.validateLabelCardinality(metric, any()) } returns cardinalityResult
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(1, response.accepted) // Should still accept with warnings
        assertEquals(0, response.rejected)
    }
    
    @Test
    fun `ingestMetrics should validate against registered labels`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = Metric(
            id = metricId,
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 100.0,
                    timestamp = Instant.now(),
                    labels = mapOf(
                        "env" to "test",
                        "unregistered_label" to "value" // Not in registered labels
                    ),
                    type = MetricType.GAUGE
                )
            )
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { metricService.getOrCreateMetric("test_metric", MetricType.GAUGE) } returns metric
        coEvery { metricService.getMetricLabels(metricId) } returns listOf("env") // Only 'env' is registered
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns 
            CardinalityValidator.CardinalityValidationResult(true, 0, 10000, emptyList(), emptyList())
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(0, response.accepted)
        assertEquals(1, response.rejected)
        assertTrue(response.errors.isNotEmpty())
        assertTrue(response.errors[0].error.contains("unregistered_label"))
    }
    
    @Test
    fun `ingestMetrics should auto-create metric if not exists`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val newMetric = Metric(
            id = metricId,
            name = "new_metric",
            type = MetricType.GAUGE,
            description = null,
            unit = null,
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "new_metric",
                    value = 100.0,
                    timestamp = Instant.now(),
                    labels = emptyMap(),
                    type = MetricType.GAUGE
                )
            )
        )
        
        coEvery { metricService.getMetricByName("new_metric") } returns null
        coEvery { metricService.getOrCreateMetric("new_metric", MetricType.GAUGE) } returns newMetric
        coEvery { metricService.getMetricLabels(metricId) } returns emptyList()
        coEvery { cardinalityValidator.validateLabelCardinality(any(), any()) } returns 
            CardinalityValidator.CardinalityValidationResult(true, 0, 10000, emptyList(), emptyList())
        
        // When
        val response = ingestionService.ingestMetrics(request)
        
        // Then
        assertEquals(1, response.accepted)
        assertEquals(0, response.rejected)
        
        coVerify { metricService.getOrCreateMetric("new_metric", MetricType.GAUGE) }
    }
}
