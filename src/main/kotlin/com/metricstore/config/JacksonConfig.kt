package com.metricstore.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Instant

/**
 * Jackson configuration to handle JSON serialization/deserialization
 * Specifically handles empty strings for Instant fields
 */
@Configuration
class JacksonConfig {
    
    @Bean
    @Primary
    fun objectMapper(): ObjectMapper {
        val mapper = ObjectMapper()
        
        // First register our custom module (needs to be before findAndRegisterModules)
        val customModule = SimpleModule("CustomInstantModule").apply {
            addDeserializer(Instant::class.java, CustomInstantDeserializer())
        }
        mapper.registerModule(customModule)
        
        // Then find and register all other modules
        mapper.findAndRegisterModules()
        
        // Ensure Kotlin module is registered
        mapper.registerModule(KotlinModule.Builder().build())
        
        return mapper
    }
    
    /**
     * Custom deserializer that treats empty strings as null for Instant fields
     * and provides better error messages
     */
    class CustomInstantDeserializer : JsonDeserializer<Instant?>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Instant? {
            val value = parser.text?.trim()
            
            return when {
                value.isNullOrEmpty() -> null  // Treat empty strings as null
                else -> {
                    try {
                        Instant.parse(value)
                    } catch (e: Exception) {
                        throw context.instantiationException(
                            Instant::class.java,
                            "Invalid timestamp format: '$value'. Expected ISO-8601 format (e.g., '2024-11-09T12:00:00Z') or leave empty for default"
                        )
                    }
                }
            }
        }
    }
}
