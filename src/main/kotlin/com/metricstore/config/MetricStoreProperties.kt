package com.metricstore.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

@ConfigurationProperties(prefix = "metric-store")
data class MetricStoreProperties @ConstructorBinding constructor(
    val ingestion: IngestionProperties,
    val storage: StorageProperties,
    val query: QueryProperties,
    val api: ApiProperties
)

data class IngestionProperties(
    val buffer: BufferProperties
)

data class BufferProperties(
    val maxSize: Int = 1,
    val flushIntervalMs: Long = 5,
    val batchSize: Int = 1,
    val workerThreads: Int = 1
)

data class StorageProperties(
    val hotTier: TierProperties,
    val warmTier: TierProperties,
    val coldTier: ColdTierProperties,
    val migration: MigrationProperties
)

data class TierProperties(
    val retentionDays: Int,
    val compressionAfterDays: Int? = null
)

data class ColdTierProperties(
    val enabled: Boolean = true,
    val s3: S3Properties,
    val parquet: ParquetProperties
)

data class S3Properties(
    val bucketName: String,
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val region: String = "us-east-1"
)

data class ParquetProperties(
    val rowGroupSize: Int = 100000,
    val pageSize: Int = 1048576,
    val compression: String = "SNAPPY"
)

data class MigrationProperties(
    val enabled: Boolean = true,
    val cron: String = "0 0 2 * * ?",
    val batchSize: Int = 10000
)

data class QueryProperties(
    val defaultLimit: Int = 100,
    val maxLimit: Int = 10000,
    val timeoutSeconds: Int = 30,
    val cache: CacheProperties
)

data class CacheProperties(
    val enabled: Boolean = true,
    val ttlSeconds: Int = 300
)

data class ApiProperties(
    val rateLimit: RateLimitProperties
)

data class RateLimitProperties(
    val enabled: Boolean = true,
    val requestsPerSecond: Int = 1000,
    val burstSize: Int = 2000
)
