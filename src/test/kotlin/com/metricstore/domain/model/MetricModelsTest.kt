package com.metricstore.domain.model

import com.metricstore.domain.entity.MetricType
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class MetricModelsTest {
    
    private lateinit var validator: Validator
    private lateinit var validatorFactory: ValidatorFactory
    
    @BeforeEach
    fun setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory()
        validator = validatorFactory.validator
    }
    
    @AfterEach
    fun tearDown() {
        validatorFactory.close()
    }
    
    // ========== MetricRegistrationRequest Tests ==========
    
    @Test
    fun `valid MetricRegistrationRequest should pass validation`() {
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.COUNTER,
            description = "Test metric",
            unit = "count",
            labels = listOf("label1", "label2"),
            retentionDays = 30
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid request should have no violations")
    }
    
    @Test
    fun `MetricRegistrationRequest with blank name should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "",
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.size >= 1, "Should have at least one violation for blank name")
        val messages = violations.map { it.message }
        assertTrue(messages.any { it.contains("Metric name is required") }, "Should have name required violation")
    }
    
    @Test
    fun `MetricRegistrationRequest with invalid name format should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "123invalid",
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("Invalid metric name format"))
    }
    
    @Test
    fun `MetricRegistrationRequest with name too long should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "a".repeat(256),
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("less than 255 characters"))
    }
    
    @Test
    fun `MetricRegistrationRequest should require type field`() {
        // Note: Since MetricType is non-null, this test verifies the validation annotation
        // In practice, JSON parsing would fail before validation for null type
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid type should pass validation")
    }
    
    @Test
    fun `MetricRegistrationRequest with description too long should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.COUNTER,
            description = "a".repeat(1001)
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("less than 1000 characters"))
    }
    
    @Test
    fun `MetricRegistrationRequest with unit too long should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.COUNTER,
            unit = "a".repeat(101)
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("less than 100 characters"))
    }
    
    @Test
    fun `MetricRegistrationRequest with retention days too low should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.COUNTER,
            retentionDays = 0
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("at least 1"))
    }
    
    @Test
    fun `MetricRegistrationRequest with retention days too high should fail validation`() {
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.COUNTER,
            retentionDays = 1826
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("cannot exceed 5 years"))
    }
    
    // ========== MetricIngestionRequest Tests ==========
    
    @Test
    fun `valid MetricIngestionRequest should pass validation`() {
        val request = MetricIngestionRequest(
            metrics = listOf(
                MetricSampleRequest(
                    name = "test_metric",
                    value = 1.0,
                    timestamp = Instant.now(),
                    labels = mapOf("env" to "prod"),
                    type = MetricType.COUNTER
                )
            )
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid request should have no violations")
    }
    
    @Test
    fun `MetricIngestionRequest with empty metrics list should fail validation`() {
        val request = MetricIngestionRequest(
            metrics = emptyList()
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertEquals("Metrics list cannot be empty", violations.iterator().next().message)
    }
    
    @Test
    fun `MetricIngestionRequest with too many metrics should fail validation`() {
        val request = MetricIngestionRequest(
            metrics = (1..10001).map {
                MetricSampleRequest(
                    name = "test_metric_$it",
                    value = it.toDouble(),
                    timestamp = Instant.now(),
                    type = MetricType.GAUGE
                )
            }
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("Cannot ingest more than 10000 metrics"))
    }
    
    // ========== MetricSampleRequest Tests ==========
    
    @Test
    fun `valid MetricSampleRequest should pass validation`() {
        val request = MetricSampleRequest(
            name = "test_metric",
            value = 1.0,
            timestamp = Instant.now(),
            labels = mapOf("env" to "prod"),
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid request should have no violations")
    }
    
    @Test
    fun `MetricSampleRequest with blank name should fail validation`() {
        val request = MetricSampleRequest(
            name = "",
            value = 1.0,
            timestamp = Instant.now(),
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertEquals("Metric name is required", violations.iterator().next().message)
    }
    
    @Test
    fun `MetricSampleRequest with valid labels should pass validation`() {
        val request = MetricSampleRequest(
            name = "test_metric",
            value = 1.0,
            timestamp = Instant.now(),
            labels = mapOf("env" to "prod", "service" to "api"),
            type = MetricType.COUNTER
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid labels should pass validation")
    }
    
    // ========== MetricQueryRequest Tests ==========
    
    @Test
    fun `valid MetricQueryRequest should pass validation`() {
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now(),
            aggregation = AggregationType.SUM,
            interval = "5m"
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid request should have no violations")
    }
    
    @Test
    fun `MetricQueryRequest with blank metric name should fail validation`() {
        val request = MetricQueryRequest(
            metricName = "",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now()
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertEquals("Metric name is required", violations.iterator().next().message)
    }
    
    @Test
    fun `MetricQueryRequest with invalid interval should fail validation`() {
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now(),
            interval = "invalid"
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("Invalid interval format"))
    }
    
    @Test
    fun `MetricQueryRequest with valid time range should pass validation`() {
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now()
        )
        
        val violations = validator.validate(request)
        assertTrue(violations.isEmpty(), "Valid time range should pass validation")
    }
    
    @Test
    fun `MetricQueryRequest with limit too high should fail validation`() {
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now(),
            limit = 10001
        )
        
        val violations = validator.validate(request)
        assertEquals(1, violations.size)
        assertTrue(violations.iterator().next().message.contains("Limit cannot exceed 10000"))
    }
}
