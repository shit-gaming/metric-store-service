package com.metricstore.service

import com.metricstore.domain.entity.Metric
import com.metricstore.domain.entity.MetricLabel
import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.MetricRegistrationRequest
import com.metricstore.repository.MetricLabelRepository
import com.metricstore.repository.MetricRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class MetricServiceTest {

    private val metricRepository = mockk<MetricRepository>()
    private val metricLabelRepository = mockk<MetricLabelRepository>()
    
    private lateinit var metricService: MetricService
    
    @BeforeEach
    fun setup() {
        metricService = MetricService(metricRepository, metricLabelRepository)
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    @Test
    fun `registerMetric should successfully register a new metric`() = runBlocking {
        // Given
        val request = MetricRegistrationRequest(
            name = "test_metric",
            type = MetricType.GAUGE,
            description = "Test metric",
            unit = "units",
            labels = listOf("label1", "label2"),
            retentionDays = 30
        )
        
        val savedMetric = Metric(
            id = UUID.randomUUID(),
            name = request.name,
            type = request.type,
            description = request.description,
            unit = request.unit,
            retentionDays = request.retentionDays!!,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(request.name) } returns null
        coEvery { metricRepository.save(any()) } returns savedMetric
        coEvery { metricLabelRepository.save(any()) } returns mockk()
        
        // When
        val result = metricService.registerMetric(request)
        
        // Then
        assertNotNull(result)
        assertEquals(request.name, result.name)
        assertEquals(request.type, result.type)
        assertEquals(request.labels, result.labels)
        
        coVerify { metricRepository.save(any()) }
        coVerify(exactly = 2) { metricLabelRepository.save(any()) } // For 2 labels
    }
    
    @Test
    fun `registerMetric should throw exception when metric already exists`() = runBlocking {
        // Given
        val request = MetricRegistrationRequest(
            name = "existing_metric",
            type = MetricType.COUNTER,
            description = "Existing metric",
            unit = "count",
            labels = listOf(),
            retentionDays = 30
        )
        
        val existingMetric = Metric(
            id = UUID.randomUUID(),
            name = request.name,
            type = MetricType.COUNTER,
            description = "Old description",
            unit = "count",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(request.name) } returns existingMetric
        
        // When & Then
        assertThrows<IllegalArgumentException> {  // Changed from IllegalStateException
            runBlocking {
                metricService.registerMetric(request)
            }
        }
        
        coVerify(exactly = 0) { metricRepository.save(any()) }
    }
    
    @Test
    fun `registerMetric with empty labels should work`() = runBlocking {
        // Given
        val request = MetricRegistrationRequest(
            name = "no_labels_metric",
            type = MetricType.COUNTER,
            description = "Metric without labels",
            unit = "count",
            labels = emptyList(),
            retentionDays = 7
        )
        
        val savedMetric = Metric(
            id = UUID.randomUUID(),
            name = request.name,
            type = request.type,
            description = request.description,
            unit = request.unit,
            retentionDays = request.retentionDays!!,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(request.name) } returns null
        coEvery { metricRepository.save(any()) } returns savedMetric
        
        // When
        val result = metricService.registerMetric(request)
        
        // Then
        assertNotNull(result)
        assertEquals(0, result.labels.size)
        
        coVerify { metricRepository.save(any()) }
        coVerify(exactly = 0) { metricLabelRepository.save(any()) } // No labels to save
    }
    
    @Test
    fun `registerMetric with null optional fields should use defaults`() = runBlocking {
        // Given
        val request = MetricRegistrationRequest(
            name = "minimal_metric",
            type = MetricType.GAUGE,
            description = null,
            unit = null,
            labels = listOf(),
            retentionDays = null
        )
        
        val savedMetric = Metric(
            id = UUID.randomUUID(),
            name = request.name,
            type = request.type,
            description = null,
            unit = null,
            retentionDays = 30, // Default
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(request.name) } returns null
        coEvery { metricRepository.save(any()) } returns savedMetric
        
        // When
        val result = metricService.registerMetric(request)
        
        // Then
        assertNotNull(result)
        assertEquals(30, result.retentionDays) // Should use default
        
        coVerify { metricRepository.save(any()) }
    }
    
    @Test
    fun `getMetricByName should return metric when it exists`() = runBlocking {
        // Given
        val metricName = "test_metric"
        val metric = Metric(
            id = UUID.randomUUID(),
            name = metricName,
            type = MetricType.GAUGE,
            description = "Test",
            unit = "units",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(metricName) } returns metric
        
        // When
        val result = metricService.getMetricByName(metricName)
        
        // Then
        assertNotNull(result)
        assertEquals(metricName, result?.name)
        coVerify { metricRepository.findByName(metricName) }
    }
    
    @Test
    fun `getMetricByName should return null when metric doesn't exist`() = runBlocking {
        // Given
        val metricName = "non_existent"
        coEvery { metricRepository.findByName(metricName) } returns null
        
        // When
        val result = metricService.getMetricByName(metricName)
        
        // Then
        assertNull(result)
        coVerify { metricRepository.findByName(metricName) }
    }
    
    @Test
    fun `getMetricById should handle UUID correctly`() = runBlocking {
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
        
        coEvery { metricRepository.findById(metricId) } returns metric
        
        // When
        val result = metricService.getMetricById(metricId)
        
        // Then
        assertNotNull(result)
        assertEquals(metricId, result?.id)
        coVerify { metricRepository.findById(metricId) }
    }
    
    @Test
    fun `getMetricLabels should return labels for a metric`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        
        coEvery { metricLabelRepository.findLabelKeysByMetricId(metricId) } returns flowOf("label1", "label2", "label3")
        
        // When
        val result = metricService.getMetricLabels(metricId)
        
        // Then
        assertEquals(3, result.size)
        assertTrue(result.contains("label1"))
        assertTrue(result.contains("label2"))
        assertTrue(result.contains("label3"))
        
        coVerify { metricLabelRepository.findLabelKeysByMetricId(metricId) }
    }
    
    @Test
    fun `getMetricLabels should return empty list when no labels exist`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        
        coEvery { metricLabelRepository.findLabelKeysByMetricId(metricId) } returns flowOf()
        
        // When
        val result = metricService.getMetricLabels(metricId)
        
        // Then
        assertTrue(result.isEmpty())
        
        coVerify { metricLabelRepository.findLabelKeysByMetricId(metricId) }
    }
    
    @Test
    fun `deleteMetric should deactivate metric when found`() = runBlocking {
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
        
        coEvery { metricRepository.findById(metricId) } returns metric
        coEvery { metricRepository.updateIsActive(metricId, false) } returns Unit
        
        // When
        metricService.deleteMetric(metricId)
        
        // Then
        coVerify {
            metricRepository.findById(metricId)
            metricRepository.updateIsActive(metricId, false)
        }
    }
    
    @Test
    fun `deleteMetric should throw exception when metric not found`() = runBlocking {
        // Given
        val metricId = UUID.randomUUID()
        coEvery { metricRepository.findById(metricId) } returns null
        
        // When & Then
        assertThrows<NoSuchElementException> {
            runBlocking {
                metricService.deleteMetric(metricId)
            }
        }
        
        coVerify { metricRepository.findById(metricId) }
        coVerify(exactly = 0) { metricRepository.save(any()) }
    }
    
    @Test
    fun `getOrCreateMetric should return existing metric`() = runBlocking {
        // Given
        val metricName = "existing_metric"
        val existingMetric = Metric(
            id = UUID.randomUUID(),
            name = metricName,
            type = MetricType.COUNTER,
            description = "Existing",
            unit = "count",
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(metricName) } returns existingMetric
        
        // When
        val result = metricService.getOrCreateMetric(metricName, MetricType.COUNTER)
        
        // Then
        assertEquals(existingMetric.id, result.id)
        assertEquals(existingMetric.name, result.name)
        
        coVerify { metricRepository.findByName(metricName) }
        coVerify(exactly = 0) { metricRepository.save(any()) }
    }
    
    @Test
    fun `getOrCreateMetric should create new metric when not exists`() = runBlocking {
        // Given
        val metricName = "new_metric"
        val newMetric = Metric(
            id = UUID.randomUUID(),
            name = metricName,
            type = MetricType.GAUGE,
            description = null,
            unit = null,
            retentionDays = 30,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        coEvery { metricRepository.findByName(metricName) } returns null
        coEvery { metricRepository.save(any()) } returns newMetric
        
        // When
        val result = metricService.getOrCreateMetric(metricName, MetricType.GAUGE)
        
        // Then
        assertEquals(metricName, result.name)
        assertEquals(MetricType.GAUGE, result.type)
        
        coVerify { 
            metricRepository.findByName(metricName)
            metricRepository.save(any())
        }
    }
}
