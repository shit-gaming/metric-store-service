package com.metricstore.domain.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.*

@Table("metric_samples")
data class MetricSample(
    @Column("time")
    val time: Instant,
    
    @Column("metric_id")
    val metricId: UUID,
    
    @Column("value")
    val value: Double,
    
    @Column("labels")
    val labels: Json = Json.of("{}")
) {
    constructor(
        time: Instant,
        metricId: UUID,
        value: Double,
        labelsMap: Map<String, String>
    ) : this(
        time,
        metricId,
        value,
        Json.of(com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(labelsMap))
    )
    
    fun getLabelsAsMap(): Map<String, String> {
        return com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(labels.asString(), object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {})
    }
}
