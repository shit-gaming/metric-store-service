package com.metricstore.domain.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("metric_labels")
data class MetricLabel(
    @Column("metric_id")
    val metricId: UUID,
    
    @Column("label_key")
    val labelKey: String
)
