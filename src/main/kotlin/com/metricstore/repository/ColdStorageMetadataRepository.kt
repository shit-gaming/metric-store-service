package com.metricstore.repository

import com.metricstore.domain.entity.ColdStorageMetadata
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ColdStorageMetadataRepository : CoroutineCrudRepository<ColdStorageMetadata, UUID> {
    
    @Query("""
        SELECT * FROM cold_storage_metadata 
        WHERE metric_id = :metricId 
        AND start_time <= :endTime 
        AND end_time >= :startTime
        ORDER BY start_time
    """)
    fun findByMetricIdAndTimeRange(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<ColdStorageMetadata>
    
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM cold_storage_metadata 
            WHERE metric_id = :metricId 
            AND DATE(start_time) = DATE(:date)
        )
    """)
    suspend fun existsByMetricIdAndDate(
        metricId: UUID,
        date: Instant
    ): Boolean
    
    @Query("""
        SELECT DISTINCT metric_id 
        FROM cold_storage_metadata
    """)
    fun findDistinctMetricIds(): Flow<UUID>
    
    @Query("""
        SELECT * FROM cold_storage_metadata 
        WHERE metric_id = :metricId 
        ORDER BY start_time DESC
        LIMIT :limit
    """)
    fun findRecentByMetricId(
        metricId: UUID,
        limit: Int = 10
    ): Flow<ColdStorageMetadata>
    
    @Query("""
        SELECT 
            COUNT(*) as file_count,
            SUM(row_count) as total_rows,
            SUM(file_size) as total_size
        FROM cold_storage_metadata
        WHERE metric_id = :metricId
    """)
    suspend fun getArchivalStats(metricId: UUID): ArchivalStats?
    
    @Query("""
        DELETE FROM cold_storage_metadata 
        WHERE created_at < :cutoffDate
    """)
    suspend fun deleteOldMetadata(cutoffDate: Instant)
}

data class ArchivalStats(
    val fileCount: Long,
    val totalRows: Long,
    val totalSize: Long
)
