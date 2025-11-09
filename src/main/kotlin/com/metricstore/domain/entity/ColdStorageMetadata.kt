package com.metricstore.domain.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Table("cold_storage_metadata")
data class ColdStorageMetadata(
    @Id
    val id: UUID = UUID.randomUUID(),
    
    @Column("metric_id")
    val metricId: UUID,
    
    @Column("start_time")
    val startTime: Instant,
    
    @Column("end_time")
    val endTime: Instant,
    
    @Column("storage_path")
    val storagePath: String,
    
    @Column("file_format")
    val fileFormat: String = "parquet",
    
    @Column("file_size_bytes")
    val fileSizeBytes: Long? = null,
    
    @Column("row_count")
    val rowCount: Long? = null,
    
    @Column("compression_ratio")
    val compressionRatio: BigDecimal? = null,
    
    @Column("labels_index")
    val labelsIndex: Json? = null,
    
    @CreatedDate
    @Column("created_at")
    val createdAt: Instant = Instant.now()
)

@Table("data_migration_jobs")
data class DataMigrationJob(
    @Id
    val id: UUID = UUID.randomUUID(),
    
    @Column("job_type")
    val jobType: MigrationJobType,
    
    @Column("status")
    val status: JobStatus,
    
    @Column("start_time")
    val startTime: Instant? = null,
    
    @Column("end_time")
    val endTime: Instant? = null,
    
    @Column("metrics_processed")
    val metricsProcessed: Int = 0,
    
    @Column("rows_migrated")
    val rowsMigrated: Long = 0,
    
    @Column("error_message")
    val errorMessage: String? = null,
    
    @CreatedDate
    @Column("created_at")
    val createdAt: Instant = Instant.now(),
    
    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant = Instant.now()
)

enum class MigrationJobType {
    HOT_TO_WARM,
    WARM_TO_COLD,
    COLD_CLEANUP
}

enum class JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
