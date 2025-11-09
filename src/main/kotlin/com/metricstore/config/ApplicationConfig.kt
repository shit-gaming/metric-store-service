package com.metricstore.config

import com.metricstore.service.MetricService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer

private val logger = KotlinLogging.logger {}

@Configuration
@EnableWebFlux
class ApplicationConfig : WebFluxConfigurer {
    
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOrigins("*")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600)
    }
    
    @Bean
    fun initializeCache(metricService: MetricService) = CommandLineRunner {
        logger.info { "Initializing application..." }
        runBlocking {
            try {
                metricService.preloadCache()
                logger.info { "Metric cache preloaded successfully" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to preload metric cache" }
            }
        }
    }
}

@Configuration
class AsyncConfig {
    @Bean
    fun taskExecutor() = org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor().apply {
        corePoolSize = 10
        maxPoolSize = 50
        queueCapacity = 100
        setThreadNamePrefix("async-")
        initialize()
    }
}
