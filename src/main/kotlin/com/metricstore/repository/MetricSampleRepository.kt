package com.metricstore.repository

import com.metricstore.domain.entity.MetricSample
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant
import java.util.*

@Repository
interface MetricSampleRepository : CoroutineCrudRepository<MetricSample, UUID> {
    
    @Query("""
        SELECT * FROM metric_samples 
        WHERE metric_id = :metricId 
        AND time BETWEEN :startTime AND :endTime
        ORDER BY time DESC
    """)
    fun findByMetricAndTimeRange(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<MetricSample>
    
    @Query("""
        SELECT * FROM metric_samples
        WHERE metric_id = :metricId
        AND time BETWEEN :startTime AND :endTime
        AND labels @> :labels::jsonb
        ORDER BY time DESC
    """)
    fun findByMetricAndTimeRangeAndLabels(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labels: String
    ): Flow<MetricSample>
    
    @Query("""
        SELECT time_bucket(CAST(:interval AS INTERVAL), time) AS bucket,
               AVG(value) as avg_value,
               SUM(value) as sum_value,
               MIN(value) as min_value,
               MAX(value) as max_value,
               COUNT(*) as count
        FROM metric_samples
        WHERE metric_id = :metricId
        AND time BETWEEN :startTime AND :endTime
        GROUP BY bucket
        ORDER BY bucket DESC
    """)
    fun aggregateByTimeBucket(
        interval: String,
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<AggregatedResult>
    
    @Query("""
        SELECT time_bucket(CAST(:interval AS INTERVAL), time) AS bucket,
               AVG(value) as avg_value,
               SUM(value) as sum_value,
               MIN(value) as min_value,
               MAX(value) as max_value,
               COUNT(*) as count
        FROM metric_samples
        WHERE metric_id = :metricId
        AND time BETWEEN :startTime AND :endTime
        AND labels @> CAST(:labels AS JSONB)
        GROUP BY bucket
        ORDER BY bucket DESC
    """)
    fun aggregateByTimeBucketWithLabels(
        interval: String,
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labels: String
    ): Flow<AggregatedResult>
    
    @Query("""
        SELECT percentile_cont(:percentile) WITHIN GROUP (ORDER BY value) as percentile_value
        FROM metric_samples
        WHERE metric_id = :metricId
        AND time BETWEEN :startTime AND :endTime
    """)
    suspend fun calculatePercentile(
        percentile: Double,
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Double?
    
    @Query("""
        WITH ordered_samples AS (
            SELECT 
                time,
                value,
                labels,
                LAG(value) OVER (PARTITION BY labels ORDER BY time) as prev_value,
                LAG(time) OVER (PARTITION BY labels ORDER BY time) as prev_time
            FROM metric_samples
            WHERE metric_id = :metricId
                AND time BETWEEN :startTime AND :endTime
        )
        SELECT 
            time,
            CASE 
                WHEN prev_value IS NULL THEN 0
                WHEN value < prev_value THEN value / EXTRACT(EPOCH FROM time - prev_time)
                ELSE (value - prev_value) / EXTRACT(EPOCH FROM time - prev_time)
            END as rate,
            labels
        FROM ordered_samples
        WHERE prev_time IS NOT NULL
        ORDER BY time DESC
    """)
    fun calculateRate(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<RateResult>
    
    @Query("""
        DELETE FROM metric_samples 
        WHERE time < :cutoffTime
    """)
    suspend fun deleteOlderThan(cutoffTime: Instant): Int
    
    // Query continuous aggregates for better performance
    @Query("""
        SELECT bucket, avg_value, sum_value, min_value, max_value, sample_count as count
        FROM metric_samples_5min
        WHERE metric_id = :metricId
        AND bucket BETWEEN :startTime AND :endTime
        AND (:labelsFilter::jsonb IS NULL OR labels @> :labelsFilter::jsonb)
        ORDER BY bucket DESC
    """)
    fun queryFrom5MinAggregates(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labelsFilter: String? = null
    ): Flow<AggregatedResult>
    
    @Query("""
        SELECT bucket, avg_value, sum_value, min_value, max_value, sample_count as count
        FROM metric_samples_hourly
        WHERE metric_id = :metricId
        AND bucket BETWEEN :startTime AND :endTime
        AND (:labelsFilter::jsonb IS NULL OR labels @> :labelsFilter::jsonb)
        ORDER BY bucket DESC
    """)
    fun queryFromHourlyAggregates(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labelsFilter: String? = null
    ): Flow<AggregatedResult>
    
    @Query("""
        SELECT bucket, avg_value, sum_value, min_value, max_value, sample_count as count
        FROM metric_samples_daily
        WHERE metric_id = :metricId
        AND bucket BETWEEN :startTime AND :endTime
        AND (:labelsFilter::jsonb IS NULL OR labels @> :labelsFilter::jsonb)
        ORDER BY bucket DESC
    """)
    fun queryFromDailyAggregates(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labelsFilter: String? = null
    ): Flow<AggregatedResult>
    
    // Use the new rate calculation function
    @Query("""
        SELECT result_time as time, rate_per_second as rate, result_labels as labels 
        FROM calculate_counter_rate(
            :metricId::uuid,
            :startTime::timestamptz,
            :endTime::timestamptz,
            :labelsFilter::jsonb
        )
    """)
    fun calculateCounterRate(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labelsFilter: String = "{}"
    ): Flow<RateResult>
    
    // Use the rate calculation from aggregates for better performance
    @Query("""
        SELECT * FROM calculate_rate_from_aggregate(
            :metricId::uuid,
            :startTime::timestamptz,
            :endTime::timestamptz,
            :interval::text,
            :labelsFilter::jsonb
        )
    """)
    fun calculateRateFromAggregate(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        interval: String,
        labelsFilter: String = "{}"
    ): Flow<RateResult>
    
    // Calculate counter increase over a time range
    @Query("""
        SELECT * FROM calculate_counter_increase(
            :metricId::uuid,
            :startTime::timestamptz,
            :endTime::timestamptz,
            :labelsFilter::jsonb
        )
    """)
    suspend fun calculateCounterIncrease(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        labelsFilter: String = "{}"
    ): CounterIncreaseResult?
    
    @Query("""
        INSERT INTO metric_samples (time, metric_id, value, labels) 
        VALUES (:time, :metricId, :value, :labels::jsonb)
        ON CONFLICT (time, metric_id, labels) DO UPDATE 
        SET value = EXCLUDED.value
    """)
    suspend fun upsert(
        time: Instant,
        metricId: UUID,
        value: Double,
        labels: String
    )
    
    // Count distinct label combinations for cardinality estimation
    @Query("""
        SELECT COUNT(DISTINCT labels) 
        FROM metric_samples 
        WHERE metric_id = :metricId 
        AND time > :startTime
    """)
    fun countDistinctLabelCombinations(
        metricId: UUID,
        startTime: Instant
    ): Mono<Int>
    
    // Archival support methods
    @Query("""
        SELECT DISTINCT metric_id 
        FROM metric_samples 
        WHERE time < :cutoffDate
    """)
    fun findDistinctMetricsWithDataBefore(cutoffDate: Instant): Flow<UUID>
    
    @Query("""
        SELECT * FROM metric_samples 
        WHERE metric_id = :metricId 
        AND time >= :startTime 
        AND time < :endTime
        ORDER BY time
        LIMIT :limit OFFSET :offset
    """)
    fun findByMetricAndTimeRangeWithPagination(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        limit: Int,
        offset: Int
    ): Flow<MetricSample>
    
    @Query("""
        DELETE FROM metric_samples 
        WHERE metric_id = :metricId 
        AND time >= :startTime 
        AND time < :endTime
        AND ctid IN (
            SELECT ctid FROM metric_samples 
            WHERE metric_id = :metricId 
            AND time >= :startTime 
            AND time < :endTime
            LIMIT :batchSize
        )
    """)
    suspend fun deleteByMetricAndTimeRangeInBatches(
        metricId: UUID,
        startTime: Instant,
        endTime: Instant,
        batchSize: Int
    )
    
    @Query("VACUUM (VERBOSE, ANALYZE) metric_samples")
    suspend fun performIncrementalVacuum()
}

data class AggregatedResult(
    val bucket: Instant,
    val avgValue: Double?,
    val sumValue: Double?,
    val minValue: Double?,
    val maxValue: Double?,
    val count: Long
)

data class RateResult(
    val time: Instant,
    val rate: Double,
    val labels: Json
)

data class CounterIncreaseResult(
    val labels: Json,
    val totalIncrease: Double,
    val startValue: Double,
    val endValue: Double
)
