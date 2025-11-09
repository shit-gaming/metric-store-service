package com.metricstore.domain.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.*

@Table("metrics")
data class Metric(
    @Id
    val id: UUID? = null,
    
    @Column("name")
    val name: String,
    
    @Column("type")
    val type: MetricType,
    
    @Column("description")
    val description: String? = null,
    
    @Column("unit")
    val unit: String? = null,
    
    @Column("is_active")
    val isActive: Boolean = true,
    
    @Column("retention_days")
    val retentionDays: Int = 30,
    
    @CreatedDate
    @Column("created_at")
    val createdAt: Instant = Instant.now(),
    
    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant = Instant.now()
)

enum class MetricType {
    COUNTER,
    GAUGE,
    HISTOGRAM,
    SUMMARY
}
