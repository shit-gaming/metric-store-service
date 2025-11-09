package com.metricstore.config

import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.SetBucketLifecycleArgs
import io.minio.messages.*
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
private val logger = KotlinLogging.logger {}

@Configuration
class MinioConfig {
    
    @Value("\${metric-store.storage.cold-tier.s3.endpoint:http://localhost:9000}")
    private lateinit var endpoint: String
    
    @Value("\${metric-store.storage.cold-tier.s3.access-key:minioadmin}")
    private lateinit var accessKey: String
    
    @Value("\${metric-store.storage.cold-tier.s3.secret-key:minioadmin}")
    private lateinit var secretKey: String
    
    @Value("\${metric-store.storage.cold-tier.s3.bucket-name:cold-storage}")
    private lateinit var bucketName: String
    
    @Value("\${metric-store.storage.cold-tier.s3.region:us-east-1}")
    private lateinit var region: String
    
    @Value("\${metric-store.storage.cold-tier.retention-days:365}")
    private var coldStorageRetentionDays: Int = 365
    
    @Bean
    fun minioClient(): MinioClient {
        val client = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .region(region)
            .build()
        
        // Initialize bucket on bean creation
        initializeBucket(client)
        return client
    }
    
    private fun initializeBucket(client: MinioClient) {
        try {
            // Check if bucket exists
            val exists = client.bucketExists(
                BucketExistsArgs.builder()
                    .bucket(bucketName)
                    .build()
            )
            
            if (!exists) {
                // Create bucket
                client.makeBucket(
                    MakeBucketArgs.builder()
                        .bucket(bucketName)
                        .region(region)
                        .build()
                )
                logger.info { "Created MinIO bucket: $bucketName" }
            } else {
                logger.info { "MinIO bucket already exists: $bucketName" }
            }
            
            logger.info { "MinIO bucket initialization complete: $bucketName" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize MinIO bucket" }
        }
    }
}
