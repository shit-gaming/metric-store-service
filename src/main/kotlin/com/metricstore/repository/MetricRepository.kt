package com.metricstore.repository

import com.metricstore.domain.entity.Metric
import com.metricstore.domain.entity.MetricLabel
import com.metricstore.domain.entity.MetricType
import kotlinx.coroutines.flow.Flow
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.*

@Repository
interface MetricRepository : CoroutineCrudRepository<Metric, UUID> {
    
    suspend fun findByName(name: String): Metric?
    
    suspend fun existsByName(name: String): Boolean
    
    fun findByType(type: MetricType): Flow<Metric>
    
    @Query("SELECT * FROM metrics WHERE is_active = true")
    fun findAllActive(): Flow<Metric>
    
    @Query("SELECT * FROM metrics WHERE is_active = true LIMIT :limit OFFSET :offset")
    fun findAllActive(limit: Int, offset: Int): Flow<Metric>
    
    @Query("SELECT * FROM metrics WHERE LOWER(name) LIKE LOWER(CONCAT('%', :search, '%'))")
    fun searchByName(search: String): Flow<Metric>
    
    @Query("""
        SELECT m.* FROM metrics m 
        WHERE m.type = :type 
        AND m.is_active = true 
        AND LOWER(m.name) LIKE LOWER(CONCAT('%', :search, '%'))
    """)
    fun searchByTypeAndName(
        type: String,
        search: String
    ): Flow<Metric>
    
    @Query("""
        UPDATE metrics 
        SET retention_days = :retentionDays, updated_at = NOW() 
        WHERE id = :id
    """)
    suspend fun updateRetentionDays(id: UUID, retentionDays: Int)
    
    @Query("""
        UPDATE metrics 
        SET is_active = :isActive, updated_at = NOW() 
        WHERE id = :id
    """)
    suspend fun updateIsActive(id: UUID, isActive: Boolean)
}

@Repository
interface MetricLabelRepository : CoroutineCrudRepository<MetricLabel, UUID> {
    
    fun findByMetricId(metricId: UUID): Flow<MetricLabel>
    
    suspend fun deleteByMetricId(metricId: UUID)
    
    @Query("""
        SELECT DISTINCT label_key 
        FROM metric_labels 
        WHERE metric_id = :metricId
    """)
    fun findLabelKeysByMetricId(metricId: UUID): Flow<String>
}
