package com.metricstore.api

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Global exception handler for consistent error responses across all APIs
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    
    data class ErrorResponse(
        val timestamp: Instant = Instant.now(),
        val status: Int,
        val error: String,
        val message: String,
        val path: String? = null
    )
    
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn { "Validation error: ${ex.message}" }
        
        val error = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = ex.message ?: "Invalid request parameters"
        )
        
        return ResponseEntity.badRequest().body(error)
    }
    
    @ExceptionHandler(NoSuchElementException::class)
    fun handleNoSuchElementException(ex: NoSuchElementException): ResponseEntity<ErrorResponse> {
        logger.warn { "Resource not found: ${ex.message}" }
        
        val error = ErrorResponse(
            status = HttpStatus.NOT_FOUND.value(),
            error = "Not Found",
            message = ex.message ?: "Resource not found"
        )
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error)
    }
    
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleWebExchangeBindException(ex: WebExchangeBindException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .joinToString(", ")
        
        logger.warn { "Binding validation error: $errors" }
        
        val error = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Validation Failed",
            message = errors
        )
        
        return ResponseEntity.badRequest().body(error)
    }
    
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val message = when (val cause = ex.cause) {
            is JsonParseException -> "Invalid JSON format"
            is JsonMappingException -> "Invalid JSON structure"
            is InvalidFormatException -> {
                if (cause.targetType.isEnum) {
                    "Invalid value for ${cause.path.joinToString(".")}: '${cause.value}'. Valid values are: ${cause.targetType.enumConstants?.joinToString(", ")}"
                } else {
                    "Invalid value format: ${cause.message}"
                }
            }
            else -> "Request body is not readable or is malformed: ${ex.message}"
        }
        
        logger.warn { "JSON parsing error: $message" }
        
        val error = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = message
        )
        
        return ResponseEntity.badRequest().body(error)
    }
    
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .joinToString(", ")
        
        logger.warn { "Method argument validation error: $errors" }
        
        val error = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Validation Failed",
            message = errors
        )
        
        return ResponseEntity.badRequest().body(error)
    }
    
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        val message = "Invalid value for parameter '${ex.name}': ${ex.value}"
        
        logger.warn { "Type mismatch error: $message" }
        
        val error = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = message
        )
        
        return ResponseEntity.badRequest().body(error)
    }
    
    @ExceptionHandler(JsonMappingException::class)
    fun handleJsonMappingException(ex: JsonMappingException): ResponseEntity<ErrorResponse> {
        val message = when (ex) {
            is InvalidFormatException -> {
                if (ex.targetType.isEnum) {
                    "Invalid value for ${ex.path.joinToString(".")}: '${ex.value}'. Valid values are: ${ex.targetType.enumConstants?.joinToString(", ")}"
                } else {
                    "Invalid format for field '${ex.path.joinToString(".")}': ${ex.value}. Expected type: ${ex.targetType.simpleName}"
                }
            }
            else -> "Invalid JSON mapping: ${ex.message}"
        }
        
        logger.warn { "JSON mapping error: $message" }
        
        val error = ErrorResponse(
            status = HttpStatus.BAD_REQUEST.value(),
            error = "Bad Request",
            message = message
        )
        
        return ResponseEntity.badRequest().body(error)
    }
    
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Unexpected error occurred" }
        
        val error = ErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            error = "Internal Server Error",
            message = "An unexpected error occurred. Please try again later."
        )
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error)
    }
}
