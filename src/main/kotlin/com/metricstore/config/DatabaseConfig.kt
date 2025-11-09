package com.metricstore.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.ConnectionFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration
@EnableR2dbcRepositories(basePackages = ["com.metricstore.repository"])
@EnableTransactionManagement
class DatabaseConfig(
    @Value("\${spring.r2dbc.url}") private val url: String,
    @Value("\${spring.r2dbc.username}") private val username: String,
    @Value("\${spring.r2dbc.password}") private val password: String
) : AbstractR2dbcConfiguration() {
    
    override fun connectionFactory(): ConnectionFactory {
        // Parse the R2DBC URL to extract host, port, and database
        val regex = """r2dbc:postgresql://([^:]+):(\d+)/(.+)""".toRegex()
        val matchResult = regex.find(url) ?: throw IllegalArgumentException("Invalid R2DBC URL: $url")
        
        val (host, port, database) = matchResult.destructured
        
        return PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(host)
                .port(port.toInt())
                .database(database)
                .username(username)
                .password(password)
                .build()
        )
    }
    
    @Bean
    fun transactionManager(connectionFactory: ConnectionFactory): ReactiveTransactionManager {
        return R2dbcTransactionManager(connectionFactory)
    }
    
    // Remove this bean as Spring Boot auto-configures it
    // @Bean
    // fun databaseClient(connectionFactory: ConnectionFactory): DatabaseClient {
    //     return DatabaseClient.create(connectionFactory)
    // }
    
    // ObjectMapper is configured in JacksonConfig for centralized configuration
}

@Configuration
class R2dbcConverterConfig {
    
    /**
     * Custom converter for Json to Map<String, String>
     */
    @Bean
    fun jsonToMapConverter(objectMapper: ObjectMapper) = object {
        fun convert(json: Json): Map<String, String> {
            return objectMapper.readValue(
                json.asString(),
                objectMapper.typeFactory.constructMapType(
                    HashMap::class.java,
                    String::class.java,
                    String::class.java
                )
            )
        }
    }
    
    /**
     * Custom converter for Map<String, String> to Json
     */
    @Bean
    fun mapToJsonConverter(objectMapper: ObjectMapper) = object {
        fun convert(map: Map<String, String>): Json {
            return Json.of(objectMapper.writeValueAsString(map))
        }
    }
}
