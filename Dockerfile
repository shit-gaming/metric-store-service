# Multi-stage build for Metric Store Service
# This Dockerfile works on both AMD64 and ARM64 architectures

# Stage 1: Build (if JAR doesn't exist)
FROM amazoncorretto:17 AS builder

WORKDIR /app

# Copy everything for build
COPY . .

# Check if JAR exists, build if not
RUN if [ ! -f "target/metric-store-service-*.jar" ]; then \
        chmod +x mvnw && \
        ./mvnw clean package -DskipTests -Dmaven.repo.local=/app/.m2; \
    fi

# Stage 2: Runtime
FROM amazoncorretto:17-alpine

# Install additional tools for health checks
RUN apk add --no-cache curl

# Create non-root user
RUN addgroup -g 1000 spring && \
    adduser -D -u 1000 -G spring spring

# Set working directory
WORKDIR /app

# Copy JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Install netcat for health checks
RUN apk add --no-cache netcat-openbsd

# Create wait script for dependencies
RUN echo '#!/bin/sh' > wait-for-deps.sh && \
    echo 'set -e' >> wait-for-deps.sh && \
    echo '' >> wait-for-deps.sh && \
    echo 'echo "Waiting for TimescaleDB..."' >> wait-for-deps.sh && \
    echo 'until nc -z ${DB_HOST:-timescaledb} ${DB_PORT:-5432}; do' >> wait-for-deps.sh && \
    echo '  echo "TimescaleDB is unavailable - sleeping"' >> wait-for-deps.sh && \
    echo '  sleep 2' >> wait-for-deps.sh && \
    echo 'done' >> wait-for-deps.sh && \
    echo 'echo "TimescaleDB is up"' >> wait-for-deps.sh && \
    echo '' >> wait-for-deps.sh && \
    echo 'echo "Waiting for MinIO..."' >> wait-for-deps.sh && \
    echo 'until curl -s http://minio:9000/minio/health/live; do' >> wait-for-deps.sh && \
    echo '  echo "MinIO is unavailable - sleeping"' >> wait-for-deps.sh && \
    echo '  sleep 2' >> wait-for-deps.sh && \
    echo 'done' >> wait-for-deps.sh && \
    echo 'echo "MinIO is up"' >> wait-for-deps.sh && \
    echo '' >> wait-for-deps.sh && \
    echo 'exec "$@"' >> wait-for-deps.sh && \
    chmod +x wait-for-deps.sh && \
    chown spring:spring wait-for-deps.sh

# Switch to non-root user
USER spring:spring

# Expose port
EXPOSE 8082

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8082/actuator/health || exit 1

# Environment variables with defaults
ENV JAVA_OPTS="-Xms512m -Xmx1024m" \
    SERVER_PORT=8082 \
    DB_HOST=timescaledb \
    DB_PORT=5432 \
    DB_NAME=metrics_db \
    DB_USER=postgres \
    DB_PASSWORD=postgres \
    S3_ENDPOINT=http://minio:9000 \
    S3_ACCESS_KEY=minioadmin \
    S3_SECRET_KEY=minioadmin

# Run the application with dependency wait
ENTRYPOINT ["./wait-for-deps.sh", "java"]
CMD ["-jar", "app.jar"]
