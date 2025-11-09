package com.metricstore.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import io.r2dbc.spi.ConnectionFactory
import org.springframework.boot.r2dbc.ConnectionFactoryBuilder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration
@Profile("test")
class TestConfig {

    companion object {
        // Shared container instance for all tests
        val postgresContainer = PostgreSQLContainer<Nothing>(
            DockerImageName.parse("timescale/timescaledb:latest-pg15")
        ).apply {
            withDatabaseName("test_metrics_db")
            withUsername("test_user")
            withPassword("test_password")
            withInitScript("test-init.sql")
            start()
        }
    }

    @Bean
    fun testConnectionFactory(): ConnectionFactory {
        return ConnectionFactoryBuilder.withUrl(
            "r2dbc:postgresql://${postgresContainer.host}:${postgresContainer.firstMappedPort}/${postgresContainer.databaseName}"
        )
            .username(postgresContainer.username)
            .password(postgresContainer.password)
            .build()
    }
}
