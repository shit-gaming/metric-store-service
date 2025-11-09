package com.metricstore.service

import com.metricstore.domain.entity.Metric
import com.metricstore.domain.entity.MetricLabel
import com.metricstore.domain.entity.MetricType
import com.metricstore.domain.model.MetricRegistrationRequest
import com.metricstore.domain.model.MetricResponse
import com.metricstore.repository.MetricLabelRepository
import com.metricstore.repository.MetricRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class MetricService(
    private val metricRepository: MetricRepository,
    private val metricLabelRepository: MetricLabelRepository
) {
    // In-memory cache for fast lookups during ingestion
    private val metricCache = ConcurrentHashMap<String, Metric>()
    
    suspend fun registerMetric(request: MetricRegistrationRequest): MetricResponse {
        // Validate metric name
        if (request.name.isBlank()) {
            throw IllegalArgumentException("Metric name cannot be blank")
        }
        if (request.name.length > 255) {
            throw IllegalArgumentException("Metric name exceeds maximum length of 255 characters")
        }
        if (!request.name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.-]*$"))) {
            throw IllegalArgumentException("Invalid metric name format: ${request.name}. Must start with a letter and contain only letters, numbers, underscore, dot, or hyphen")
        }
        
        // Validate labels
        if (request.labels.size > 10) {
            throw IllegalArgumentException("Cannot have more than 10 labels per metric")
        }
        request.labels.forEach { label ->
            if (label.isBlank()) {
                throw IllegalArgumentException("Label name cannot be blank")
            }
            if (label.length > 100) {
                throw IllegalArgumentException("Label name '$label' exceeds maximum length of 100 characters")
            }
            if (!label.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
                throw IllegalArgumentException("Invalid label name format: '$label'. Must start with a letter and contain only letters, numbers, or underscore")
            }
        }
        
        // Validate retention days
        if ((request.retentionDays ?: 30) < 1) {
            throw IllegalArgumentException("Retention days must be at least 1")
        }
        if ((request.retentionDays ?: 30) > 1825) { // 5 years
            throw IllegalArgumentException("Retention days cannot exceed 1825 (5 years)")
        }
        
        logger.info { "Registering metric: ${request.name}" }
        
        // Check if metric already exists
        metricRepository.findByName(request.name)?.let {
            logger.warn { "Metric ${request.name} already exists" }
            throw IllegalArgumentException("Metric ${request.name} already exists")
        }
        
        val metric = Metric(
            id = null,  // Let database generate the ID
            name = request.name,
            type = request.type,
            description = request.description,
            unit = request.unit,
            retentionDays = request.retentionDays ?: 30
        )
        
        val savedMetric = metricRepository.save(metric)
        
        // Save labels separately if metric has an id
        savedMetric.id?.let { metricId ->
            request.labels.forEach { label ->
                metricLabelRepository.save(MetricLabel(metricId, label))
            }
        }
        
        metricCache[savedMetric.name] = savedMetric
        
        logger.info { "Successfully registered metric: ${savedMetric.name}" }
        return savedMetric.toResponse(request.labels)
    }
    
    suspend fun getMetricByName(name: String): Metric? {
        return metricCache[name] ?: metricRepository.findByName(name)?.also {
            metricCache[name] = it
        }
    }
    
    suspend fun getOrCreateMetric(name: String, type: MetricType): Metric {
        return getMetricByName(name) ?: registerMetric(
            MetricRegistrationRequest(
                name = name,
                type = type,
                description = "Auto-registered metric",
                labels = emptyList()
            )
        ).let { getMetricByName(name)!! }
    }
    
    suspend fun getMetricById(id: UUID): Metric? {
        return metricRepository.findById(id)
    }
    
    suspend fun listMetrics(
        type: MetricType? = null,
        search: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<MetricResponse> {
        val metrics = when {
            type != null && !search.isNullOrBlank() -> 
                metricRepository.searchByTypeAndName(type.name, search)
            type != null -> 
                metricRepository.findByType(type)
            !search.isNullOrBlank() -> 
                metricRepository.searchByName(search)
            else -> 
                metricRepository.findAllActive(limit, offset)
        }
        
        return metrics.map { metric ->
            val labels = metricLabelRepository.findLabelKeysByMetricId(metric.id!!).toList()
            metric.toResponse(labels)
        }
    }
    
    @Transactional
    @CacheEvict(value = ["metrics", "metric-by-id"], allEntries = true)
    suspend fun updateMetric(id: UUID, retentionDays: Int?, isActive: Boolean?): MetricResponse {
        // Validate retention days if provided
        retentionDays?.let {
            if (it < 1) {
                throw IllegalArgumentException("Retention days must be at least 1")
            }
            if (it > 1825) { // 5 years
                throw IllegalArgumentException("Retention days cannot exceed 1825 (5 years)")
            }
        }
        
        val metric = metricRepository.findById(id)
            ?: throw NoSuchElementException("Metric not found: $id")
        
        val updatedMetric = metric.copy(
            retentionDays = retentionDays ?: metric.retentionDays,
            isActive = isActive ?: metric.isActive,
            updatedAt = java.time.Instant.now()
        )
        
        val saved = metricRepository.save(updatedMetric)
        metricCache[saved.name] = saved
        
        val labels = metricLabelRepository.findLabelKeysByMetricId(saved.id!!).toList()
        return saved.toResponse(labels)
    }
    
    @Transactional
    @CacheEvict(value = ["metrics", "metric-by-id"], allEntries = true)
    suspend fun deleteMetric(id: UUID) {
        val metric = metricRepository.findById(id)
            ?: throw NoSuchElementException("Metric not found: $id")
        
        metricRepository.updateIsActive(id, false)
        metricCache.remove(metric.name)
        
        logger.info { "Deactivated metric: ${metric.name}" }
    }
    
    suspend fun preloadCache() {
        logger.info { "Preloading metric cache..." }
        val metrics = metricRepository.findAll().toList()
        metrics.forEach { metricCache[it.name] = it }
        logger.info { "Loaded ${metrics.size} metrics into cache" }
    }
    
    suspend fun getMetricLabels(metricId: UUID): List<String> {
        return metricLabelRepository.findLabelKeysByMetricId(metricId).toList()
    }
    
    private fun Metric.toResponse(labels: List<String> = emptyList()) = MetricResponse(
        id = id ?: UUID.randomUUID(),  // Provide a default UUID if null
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
