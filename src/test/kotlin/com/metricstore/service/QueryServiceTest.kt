package com.metricstore.service

import com.metricstore.domain.entity.Metric
import com.metricstore.domain.entity.MetricSample
import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.AggregationType
import com.metricstore.domain.model.MetricQueryRequest
import com.metricstore.repository.AggregatedResult
import com.metricstore.repository.MetricSampleRepository
import com.metricstore.repository.RateResult
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class QueryServiceTest {

    private val metricService = mockk<MetricService>()
    private val metricSampleRepository = mockk<MetricSampleRepository>()
    
    private lateinit var queryService: QueryService
    
    @BeforeEach
    fun setup() {
        queryService = QueryService(metricService, metricSampleRepository)
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `queryMetrics should return raw data when no aggregation specified`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        val samples = listOf(
            MetricSample(Instant.now(), metricId, 100.0, mapOf("env" to "test")),
            MetricSample(Instant.now().minusSeconds(60), metricId, 90.0, mapOf("env" to "test")),
            MetricSample(Instant.now().minusSeconds(120), metricId, 80.0, mapOf("env" to "test"))
        )
        
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(300),
            endTime = Instant.now(),
            aggregation = null,
            interval = null,
            labels = emptyMap(),
            limit = 100
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.findByMetricAndTimeRange(metricId, any(), any()) 
        } returns flowOf(*samples.toTypedArray())
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertEquals("test_metric", response.metric)
        assertEquals(3, response.totalPoints)
        assertEquals(3, response.data.size)
        assertNull(response.aggregation)
        
        coVerify { 
            metricSampleRepository.findByMetricAndTimeRange(metricId, any(), any()) 
        }
    }
    
    @Test
    fun `queryMetrics should throw exception for non-existent metric`() = runBlocking {
        // Given
        val request = MetricQueryRequest(
            metricName = "non_existent",
            startTime = Instant.now().minusSeconds(300),
            endTime = Instant.now()
        )
        
        coEvery { metricService.getMetricByName("non_existent") } returns null
        
        // When & Then
        assertThrows<NoSuchElementException> {
            runBlocking {
                queryService.queryMetrics(request)
            }
        }
        
        coVerify { metricService.getMetricByName("non_existent") }
    }
    
    @Test
    fun `queryMetrics should validate time range - start after end`(): Unit = runBlocking {
        // Given - Start time after end time
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now(),
            endTime = Instant.now().minusSeconds(300) // End before start
        )
        
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        
        // When & Then
        assertThrows<IllegalArgumentException> {
            runBlocking {
                queryService.queryMetrics(request)
            }
        }
    }
    
    @Test
    fun `queryMetrics should validate time range not exceeding 90 days`() = runBlocking {
        // Given - Time range > 90 days
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(91 * 24 * 3600), // 91 days ago
            endTime = Instant.now()
        )
        
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        
        // When & Then
        assertThrows<IllegalArgumentException> {
            runBlocking {
                queryService.queryMetrics(request)
            }
        }
    }
    
    @Test
    fun `queryMetrics should handle null time range with defaults`() = runBlocking {
        // Given - No time range specified
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = null,
            endTime = null
        )
        
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.findByMetricAndTimeRange(metricId, any(), any()) 
        } returns flowOf()
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertNotNull(response)
        // Should use default time range (last 1 hour)
        coVerify { 
            metricSampleRepository.findByMetricAndTimeRange(
                metricId, 
                match { it.isBefore(Instant.now().minusSeconds(3500)) }, // Around 1 hour ago
                match { it.isAfter(Instant.now().minusSeconds(100)) }    // Around now
            ) 
        }
    }
    
    @Test
    fun `queryMetrics should validate interval format`(): Unit = runBlocking {
        // Given - Invalid interval format
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now(),
            aggregation = AggregationType.AVG,
            interval = "invalid" // Should be like "5m", "1h", etc.
        )
        
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        
        // When & Then
        assertThrows<IllegalArgumentException> {
            runBlocking {
                queryService.queryMetrics(request)
            }
        }
    }
    
    @Test
    fun `queryMetrics should accept valid interval formats`() = runBlocking {
        // Given - Valid interval formats
        val validIntervals = listOf("5s", "10m", "1h", "1d")
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.aggregateByTimeBucket(any(), any(), any(), any()) 
        } returns flowOf()
        
        validIntervals.forEach { interval ->
            val request = MetricQueryRequest(
                metricName = "test_metric",
                startTime = Instant.now().minusSeconds(3600),
                endTime = Instant.now(),
                aggregation = AggregationType.AVG,
                interval = interval
            )
            
            // Should not throw
            assertDoesNotThrow {
                runBlocking {
                    queryService.queryMetrics(request)
                }
            }
        }
    }
    
    @Test
    fun `queryMetrics should handle RATE aggregation for counter metrics`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_counter", MetricType.COUNTER)
        
        val request = MetricQueryRequest(
            metricName = "test_counter",
            startTime = Instant.now().minusSeconds(300),
            endTime = Instant.now(),
            aggregation = AggregationType.RATE
        )
        
        val rateResults = listOf(
            RateResult(Instant.now(), 10.0, Json.of("""{"env": "test"}""")),
            RateResult(Instant.now().minusSeconds(60), 8.0, Json.of("""{"env": "test"}"""))
        )
        
        coEvery { metricService.getMetricByName("test_counter") } returns metric
        coEvery { 
            metricSampleRepository.calculateRate(metricId, any(), any()) 
        } returns flowOf(*rateResults.toTypedArray())
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertEquals(AggregationType.RATE, response.aggregation)
        assertEquals(2, response.totalPoints)
        assertEquals(10.0, response.data[0].value)
        
        coVerify { metricSampleRepository.calculateRate(metricId, any(), any()) }
    }
    
    @Test
    fun `queryMetrics should reject RATE aggregation for gauge metrics`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_gauge", MetricType.GAUGE)
        
        val request = MetricQueryRequest(
            metricName = "test_gauge",
            startTime = Instant.now().minusSeconds(300),
            endTime = Instant.now(),
            aggregation = AggregationType.RATE
        )
        
        coEvery { metricService.getMetricByName("test_gauge") } returns metric
        
        // When & Then
        val exception = assertThrows<IllegalArgumentException> {
            runBlocking {
                queryService.queryMetrics(request)
            }
        }
        assertTrue(exception.message?.contains("counter") ?: false)
    }
    
    @Test
    fun `queryMetrics should handle all percentile aggregations`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        val percentiles = mapOf(
            AggregationType.P50 to 0.50,
            AggregationType.P75 to 0.75,
            AggregationType.P90 to 0.90,
            AggregationType.P95 to 0.95,
            AggregationType.P99 to 0.99
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        
        percentiles.forEach { (aggregation, percentile) ->
            coEvery { 
                metricSampleRepository.calculatePercentile(percentile, metricId, any(), any()) 
            } returns (percentile * 100)
            
            val request = MetricQueryRequest(
                metricName = "test_metric",
                startTime = Instant.now().minusSeconds(300),
                endTime = Instant.now(),
                aggregation = aggregation
            )
            
            // When
            val response = queryService.queryMetrics(request)
            
            // Then
            assertEquals(aggregation, response.aggregation)
            assertEquals(1, response.totalPoints)
            assertEquals(percentile * 100, response.data[0].value, 0.01)
        }
    }
    
    @Test
    fun `queryMetrics should handle time-bucketed aggregations`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(900),
            endTime = Instant.now(),
            aggregation = AggregationType.AVG,
            interval = "5m"
        )
        
        val aggregatedResults = listOf(
            AggregatedResult(
                bucket = Instant.now(),
                avgValue = 100.0,
                sumValue = 300.0,
                minValue = 90.0,
                maxValue = 110.0,
                count = 3
            ),
            AggregatedResult(
                bucket = Instant.now().minusSeconds(300),
                avgValue = 80.0,
                sumValue = 240.0,
                minValue = 70.0,
                maxValue = 90.0,
                count = 3
            )
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.aggregateByTimeBucket("5 minutes", metricId, any(), any()) 
        } returns flowOf(*aggregatedResults.toTypedArray())
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertEquals(AggregationType.AVG, response.aggregation)
        assertEquals("5m", response.interval)
        assertEquals(2, response.totalPoints)
        assertEquals(100.0, response.data[0].value)
        assertEquals(80.0, response.data[1].value)
    }
    
    @Test
    fun `queryMetrics should handle all basic aggregation types`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        val aggregationTypes = listOf(
            AggregationType.SUM,
            AggregationType.AVG,
            AggregationType.MIN,
            AggregationType.MAX,
            AggregationType.COUNT
        )
        
        val aggregatedResult = AggregatedResult(
            bucket = Instant.now(),
            avgValue = 100.0,
            sumValue = 1000.0,
            minValue = 50.0,
            maxValue = 150.0,
            count = 10
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.aggregateByTimeBucket(any(), metricId, any(), any()) 
        } returns flowOf(aggregatedResult)
        
        aggregationTypes.forEach { aggType ->
            val request = MetricQueryRequest(
                metricName = "test_metric",
                startTime = Instant.now().minusSeconds(300),
                endTime = Instant.now(),
                aggregation = aggType,
                interval = "5m"
            )
            
            // When
            val response = queryService.queryMetrics(request)
            
            // Then
            assertEquals(aggType, response.aggregation)
            assertEquals(1, response.totalPoints)
            
            val expectedValue = when(aggType) {
                AggregationType.SUM -> 1000.0
                AggregationType.AVG -> 100.0
                AggregationType.MIN -> 50.0
                AggregationType.MAX -> 150.0
                AggregationType.COUNT -> 10.0
                else -> 0.0
            }
            assertEquals(expectedValue, response.data[0].value, 0.01)
        }
    }
    
    @Test
    fun `queryMetrics should respect limit parameter`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        // Create 10 samples
        val samples = (1..10).map { i ->
            MetricSample(
                Instant.now().minusSeconds(i * 60L),
                metricId,
                i * 10.0,
                mapOf("env" to "test")
            )
        }
        
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now(),
            limit = 5 // Limit to 5 results
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.findByMetricAndTimeRange(metricId, any(), any()) 
        } returns flowOf(*samples.toTypedArray())
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertEquals(5, response.data.size) // Should respect limit
        assertEquals(5, response.totalPoints)
    }
    
    @Test
    fun `queryMetrics should handle empty results gracefully`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(300),
            endTime = Instant.now()
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.findByMetricAndTimeRange(metricId, any(), any()) 
        } returns flowOf() // Empty flow
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertEquals(0, response.data.size)
        assertEquals(0, response.totalPoints)
        assertEquals("test_metric", response.metric)
    }
    
    @Test
    fun `queryMetrics should handle label filters with JSON`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        val metric = createTestMetric(metricId, "test_metric", MetricType.GAUGE)
        val labels = mapOf("env" to "prod", "service" to "api")
        
        val samples = listOf(
            MetricSample(Instant.now(), metricId, 100.0, labels)
        )
        
        val request = MetricQueryRequest(
            metricName = "test_metric",
            startTime = Instant.now().minusSeconds(300),
            endTime = Instant.now(),
            labels = labels
        )
        
        coEvery { metricService.getMetricByName("test_metric") } returns metric
        coEvery { 
            metricSampleRepository.findByMetricAndTimeRangeAndLabels(
                metricId, any(), any(), any()
            ) 
        } returns flowOf(*samples.toTypedArray())
        
        // When
        val response = queryService.queryMetrics(request)
        
        // Then
        assertEquals(1, response.data.size)
        assertEquals(100.0, response.data[0].value)
        
        coVerify { 
            metricSampleRepository.findByMetricAndTimeRangeAndLabels(
                metricId, any(), any(), any()
            ) 
        }
    }
    
    private fun createTestMetric(
        id: UUID,
        name: String,
        type: MetricType
    ): Metric {
        return Metric(
            id = id,
            name = name,
            type = type,
            description = "Test metric",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
}
