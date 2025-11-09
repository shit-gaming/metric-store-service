package com.metricstore.service

import com.metricstore.domain.entity.Metric
import com.metricstore.domain.entity.MetricType
import com.metricstore.repository.MetricSampleRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*

class CardinalityValidatorTest {

    private val metricSampleRepository = mockk<MetricSampleRepository>()
    
    private lateinit var cardinalityValidator: CardinalityValidator
    
    @BeforeEach
    fun setup() {
        cardinalityValidator = CardinalityValidator(
            metricSampleRepository = metricSampleRepository,
            maxSeriesPerMetric = 10000,
            maxLabelsPerMetric = 10,
            maxLabelValueLength = 100,
            warningThreshold = 0.8,
            checkWindowHours = 24
        )
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `validateLabelCardinality should pass for valid labels`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf(
            "environment" to "production",
            "service" to "api-gateway",
            "region" to "us-east-1"
        )
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertEquals(100, result.currentCardinality)
        assertEquals(10000, result.maxAllowed)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `validateLabelCardinality should pass with empty labels`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = emptyMap<String, String>()
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(1)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }
    
    @Test
    fun `validateLabelCardinality should reject too many labels`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = (1..12).associate { "label$it" to "value$it" } // 12 labels > 10 limit
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Too many labels"))
        assertTrue(result.errors[0].contains("12 exceeds maximum of 10"))
    }
    
    @Test
    fun `validateLabelCardinality should reject exactly at label limit`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = (1..11).associate { "label$it" to "value$it" } // 11 labels > 10 limit
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
    }
    
    @Test
    fun `validateLabelCardinality should accept exactly at label limit`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = (1..10).associate { "label$it" to "value$it" } // Exactly 10 labels
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `validateLabelCardinality should reject long label values`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val longValue = "x".repeat(101) // 101 chars > 100 limit
        val labels = mapOf(
            "environment" to "production",
            "description" to longValue
        )
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Label 'description' value is too long"))
        assertTrue(result.errors[0].contains("101 characters exceeds maximum of 100"))
    }
    
    @Test
    fun `validateLabelCardinality should accept exactly at value length limit`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val exactLengthValue = "x".repeat(100) // Exactly 100 chars
        val labels = mapOf("description" to exactLengthValue)
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }
    
    @Test
    fun `validateLabelCardinality should detect multiple long values`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val longValue1 = "x".repeat(101)
        val longValue2 = "y".repeat(150)
        val labels = mapOf(
            "field1" to longValue1,
            "field2" to longValue2,
            "field3" to "normal"
        )
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertEquals(2, result.errors.size) // Two long values
        assertTrue(result.errors.any { it.contains("field1") })
        assertTrue(result.errors.any { it.contains("field2") })
    }
    
    @Test
    fun `validateLabelCardinality should warn about high cardinality labels`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf(
            "environment" to "production",
            "user_id" to "user123", // High cardinality label
            "session_id" to "sess456", // High cardinality label
            "request_id" to "req789" // High cardinality label
        )
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid) // Should still be valid
        assertEquals(3, result.warnings.size) // But with 3 warnings
        assertTrue(result.warnings.any { it.contains("user_id") && it.contains("high-cardinality") })
        assertTrue(result.warnings.any { it.contains("session_id") && it.contains("high-cardinality") })
        assertTrue(result.warnings.any { it.contains("request_id") && it.contains("high-cardinality") })
    }
    
    @Test
    fun `validateLabelCardinality should not warn about normal labels`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf(
            "environment" to "production",
            "region" to "us-east-1",
            "service" to "api",
            "status" to "success",
            "method" to "GET"
        )
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(100)
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertTrue(result.warnings.isEmpty()) // No warnings for normal labels
    }
    
    @Test
    fun `validateLabelCardinality should warn when approaching limit`() = runBlocking {
        val metric = createTestMetric()
        val labels = mapOf("environment" to "production")
        coEvery { metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) } returns Mono.just(8001) // 80.01% > 80% threshold
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        assertTrue(result.isValid)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("approaching cardinality limit"))
    }
    
    @Test
    fun `validateLabelCardinality should warn above threshold`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf("environment" to "production")
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(9000) // 90% > 80% threshold
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("approaching cardinality limit"))
    }
    
    @Test
    fun `validateLabelCardinality should not warn below threshold`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf("environment" to "production")
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(7999) // Just below 80% threshold
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid)
        assertTrue(result.warnings.isEmpty()) // No warning below threshold
    }
    
    @Test
    fun `validateLabelCardinality should reject when at limit`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf("environment" to "production")
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(10000) // Exactly at limit
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("reached maximum cardinality"))
        assertTrue(result.errors[0].contains("10000 series"))
    }
    
    @Test
    fun `validateLabelCardinality should reject when above limit`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf("environment" to "production")
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(10001) // Above limit
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("reached maximum cardinality"))
    }
    
    @Test
    fun `validateLabelCardinality should handle repository errors gracefully`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val labels = mapOf("environment" to "production")
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } throws RuntimeException("Database error")
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertTrue(result.isValid) // Should not fail validation on error
        assertEquals(0, result.currentCardinality) // Should return 0
    }
    
    @Test
    fun `validateLabelCardinality should handle multiple validation failures`() = runBlocking {
        // Given
        val metric = createTestMetric()
        val longValue = "x".repeat(150)
        // Too many labels (12), long values, and high cardinality labels
        val labels = (1..12).associate { "label$it" to longValue } +
                     mapOf("user_id" to "user123", "session_id" to "sess456")
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metric.id!!, any()) 
        } returns Mono.just(9500) // Also above warning threshold
        
        // When
        val result = cardinalityValidator.validateLabelCardinality(metric, labels)
        
        // Then
        assertFalse(result.isValid)
        assertTrue(result.errors.size > 1) // Multiple errors
        assertTrue(result.warnings.size > 1) // Multiple warnings
    }
    
    @Test
    fun `getCardinalityStats should return correct statistics`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metricId, any()) 
        } returns Mono.just(5000)
        
        // When
        val stats = cardinalityValidator.getCardinalityStats(metricId)
        
        // Then
        assertEquals(5000, stats["currentCardinality"])
        assertEquals(10000, stats["maxAllowed"])
        assertEquals(50.0, stats["utilizationPercent"])
        assertFalse(stats["isWarning"] as Boolean) // 50% < 80% threshold
        assertFalse(stats["isCritical"] as Boolean)
        
        coVerify { 
            metricSampleRepository.countDistinctLabelCombinations(metricId, any()) 
        }
    }
    
    @Test
    fun `getCardinalityStats should mark as warning when above threshold`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metricId, any()) 
        } returns Mono.just(8500) // 85% > 80% threshold
        
        // When
        val stats = cardinalityValidator.getCardinalityStats(metricId)
        
        // Then
        assertEquals(8500, stats["currentCardinality"])
        assertEquals(85.0, stats["utilizationPercent"])
        assertTrue(stats["isWarning"] as Boolean)
        assertFalse(stats["isCritical"] as Boolean)
    }
    
    @Test
    fun `getCardinalityStats should mark as critical when at limit`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        
        coEvery { 
            metricSampleRepository.countDistinctLabelCombinations(metricId, any()) 
        } returns Mono.just(10000) // At limit
        
        // When
        val stats = cardinalityValidator.getCardinalityStats(metricId)
        
        // Then
        assertEquals(10000, stats["currentCardinality"])
        assertEquals(100.0, stats["utilizationPercent"])
        assertTrue(stats["isWarning"] as Boolean)
        assertTrue(stats["isCritical"] as Boolean)
    }
    
    @Test
    fun `cleanupCache should not throw exceptions`() {
        // Simply ensure the cleanup method doesn't throw
        assertDoesNotThrow {
            cardinalityValidator.cleanupCache()
        }
    }
    
    private fun createTestMetric(): Metric {
        return Metric(
            id = UUID.randomUUID(),
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test metric",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
}
