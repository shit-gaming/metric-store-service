# Metric Store Service - Implementation Plan

## Overview
This document outlines the step-by-step implementation plan for the metric-store service with tiered storage capabilities.

## Critical Design Decisions

### Cardinality Protection (Preventing Prometheus-like Failures)
Unlike Prometheus which can suffer from cardinality explosion leading to OOM crashes, our system implements:
- **Proactive Validation**: Reject high-cardinality metrics at ingestion time
- **JSONB Storage**: Flexible label storage without pre-allocated series memory
- **Configurable Limits**: Adjustable per-deployment based on resources
- **Pattern Detection**: Automatically warn about known high-cardinality patterns

This prevents the common failure modes in Prometheus:
- Memory explosion from unique series (each series ~2KB in Prometheus)
- Index bloat causing slow queries
- TSDB corruption from exceeding limits

## Phase 1: Project Setup & Infrastructure (Day 1)

### 1.1 Initialize Project Structure
- [ ] Create Go module: `go mod init github.com/metric-store`
- [ ] Set up directory structure
- [ ] Create Makefile for common tasks
- [ ] Set up Git repository and .gitignore

### 1.2 Development Environment
- [ ] Docker Compose configuration
  - PostgreSQL with TimescaleDB
  - MinIO for S3-compatible storage
  - PgBouncer for connection pooling
  - Redis for caching (optional)
- [ ] Environment configuration files
- [ ] Local development scripts

### 1.3 Dependencies
- [ ] Core dependencies:
  ```
  - gin-gonic/gin (HTTP framework)
  - lib/pq (PostgreSQL driver)
  - timescale/timescaledb-go
  - minio/minio-go (S3 client)
  - apache/arrow/go (Parquet support)
  - go-redis/redis
  - spf13/viper (configuration)
  - uber-go/zap (logging)
  ```

## Phase 2: Database Layer (Day 2)

### 2.1 Database Migrations
- [ ] Install golang-migrate
- [ ] Create migration files:
  - 001_create_metrics_table
  - 002_create_samples_hypertable
  - 003_create_cold_storage_metadata
  - 004_create_continuous_aggregates
  - 005_add_compression_policies
- [ ] Migration runner implementation

### 2.2 Database Client
- [ ] Connection pool configuration
- [ ] Database client wrapper
- [ ] Transaction support
- [ ] Query builders
- [ ] Error handling

### 2.3 TimescaleDB Setup
- [ ] Hypertable creation
- [ ] Compression policies
- [ ] Retention policies
- [ ] Continuous aggregates

## Phase 3: Core Models & Storage (Day 3)

### 3.1 Domain Models
- [ ] Metric model
- [ ] Sample model
- [ ] Query model
- [ ] Migration job model

### 3.2 Storage Interface
- [ ] Storage interface definition
- [ ] TimescaleDB implementation
- [ ] Cold storage interface
- [ ] S3/MinIO implementation

### 3.3 Metric Registry
- [ ] In-memory cache
- [ ] Database persistence
- [ ] Auto-registration logic
- [ ] Validation rules

## Phase 4: Ingestion Pipeline (Day 4)

### 4.1 Write Buffer
- [ ] Buffer implementation
- [ ] Size-based flushing
- [ ] Time-based flushing
- [ ] Batch writer
- [ ] Error handling & retries

### 4.2 Ingestion Service
- [ ] Single metric ingestion
- [ ] Batch ingestion
- [ ] NDJSON parser
- [ ] Validation pipeline
- [ ] Metric auto-registration

### 4.3 Background Workers
- [ ] Buffer flush worker
- [ ] Compression worker
- [ ] Worker pool management

### 4.4 Cardinality Protection ⚠️ CRITICAL
- [x] **CardinalityValidator Service**
  - [x] Real-time cardinality estimation
  - [x] Configurable limits (max series per metric)
  - [x] Label count validation
  - [x] Label value length validation
- [x] **High-Cardinality Detection**
  - [x] Pattern-based detection (user_id, session_id, etc.)
  - [x] Warning thresholds (80% of limit)
  - [x] Automatic rejection at limits
- [x] **Rate Limiting**
  - [x] Bucket4j integration for DoS prevention
  - [x] Cardinality check caching
- [x] **Configuration**
  - [x] `max-series-per-metric`: 10,000 (configurable)
  - [x] `max-labels-per-metric`: 10
  - [x] `max-label-value-length`: 100
  - [x] `warning-threshold`: 0.8

## Phase 5: Query Engine (Day 5)

### 5.1 Query Parser
- [ ] Query parameter parsing
- [ ] Time range validation
- [ ] Label filter parsing
- [ ] Aggregation selection

### 5.2 Query Executor
- [ ] Hot tier queries
- [ ] Cold tier queries
- [ ] Unified query interface
- [ ] Query optimization
- [ ] Result formatting

### 5.3 Aggregations
- [ ] Basic aggregations (sum, avg, min, max)
- [ ] Percentile calculations
- [ ] Rate calculations for counters
- [ ] Time bucketing

## Phase 6: Tiered Storage (Day 6)

### 6.1 Migration Service
- [ ] Chunk identification
- [ ] Parquet exporter
- [ ] S3 uploader
- [ ] Metadata tracking
- [ ] Chunk deletion

### 6.2 Cold Storage Query
- [ ] S3 file listing
- [ ] Parquet reader
- [ ] Query pushdown
- [ ] Result merging
- [ ] Caching layer

### 6.3 Scheduled Jobs
- [ ] Cron job scheduler
- [ ] Hot to warm migration (10 days)
- [ ] Warm to cold migration (30 days)
- [ ] Job monitoring
- [ ] Failure handling

## Phase 7: REST API (Day 7)

### 7.1 API Gateway
- [ ] Router setup (Gin)
- [ ] Middleware chain
- [ ] Request/Response models
- [ ] OpenAPI documentation

### 7.2 Endpoints
- [ ] POST `/api/v1/metrics/register`
- [ ] POST `/api/v1/metrics/ingest`
- [ ] POST `/api/v1/metrics/ingest/batch`
- [ ] GET `/api/v1/metrics`
- [ ] GET `/api/v1/metrics/query`
- [ ] GET `/api/v1/health`
- [ ] GET `/api/v1/metrics/export`

### 7.3 Middleware
- [ ] Authentication (API key)
- [ ] Rate limiting
- [ ] Request logging
- [ ] Error handling
- [ ] CORS support

## Phase 8: Monitoring & Observability (Day 8)

### 8.1 Metrics
- [ ] Prometheus metrics
- [ ] Custom metrics (ingestion rate, query latency)
- [ ] Database metrics
- [ ] Storage metrics

### 8.2 Logging
- [ ] Structured logging
- [ ] Log levels
- [ ] Request tracing
- [ ] Error tracking

### 8.3 Health Checks
- [ ] Liveness probe
- [ ] Readiness probe
- [ ] Database connectivity
- [ ] S3 connectivity

## Phase 9: Testing (Day 9)

### 9.1 Unit Tests
- [ ] Model tests
- [ ] Service tests
- [ ] Handler tests
- [ ] Buffer tests

### 9.2 Integration Tests
- [ ] Database integration
- [ ] S3 integration
- [ ] End-to-end API tests
- [ ] Migration tests

### 9.3 Performance Tests
- [ ] Load testing
- [ ] Ingestion benchmarks
- [ ] Query benchmarks
- [ ] Memory profiling

## Phase 10: Deployment & Documentation (Day 10)

### 10.1 Containerization
- [ ] Dockerfile
- [ ] Multi-stage builds
- [ ] Image optimization
- [ ] Security scanning

### 10.2 Kubernetes Manifests
- [ ] Deployment configuration
- [ ] Service definitions
- [ ] ConfigMaps & Secrets
- [ ] HPA configuration
- [ ] PVC for storage

### 10.3 Documentation
- [ ] API documentation
- [ ] Deployment guide
- [ ] Configuration guide
- [ ] Troubleshooting guide
- [ ] Architecture diagrams

## Implementation Order

### Week 1: Foundation
1. **Day 1**: Project setup, Docker Compose
2. **Day 2**: Database schema, migrations
3. **Day 3**: Core models, storage interface
4. **Day 4**: Basic ingestion (no buffer)
5. **Day 5**: Basic query engine

### Week 2: Advanced Features
6. **Day 6**: Write buffer implementation
7. **Day 7**: REST API complete
8. **Day 8**: Tiered storage implementation
9. **Day 9**: Testing suite
10. **Day 10**: Monitoring & deployment

## Key Milestones

| Milestone | Description | Success Criteria |
|-----------|-------------|------------------|
| M1: Basic Ingestion | Single metric ingestion working | Can store and retrieve metrics |
| M2: Batch Processing | Buffer and batch ingestion | 10K metrics/batch successful |
| M3: Query Engine | Complex queries working | Aggregations return correct results |
| M4: Tiered Storage | Cold storage migration | Data migrates after 10 days |
| M5: Production Ready | Full feature complete | All tests pass, documented |

## Risk Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Parquet library issues | High | Fallback to CSV format |
| S3 latency for queries | Medium | Implement aggressive caching |
| Buffer overflow | High | Implement backpressure |
| Migration job failures | Medium | Retry logic with exponential backoff |

## Development Tools

```bash
# Development commands
make dev        # Start local environment
make test       # Run tests
make build      # Build binary
make migrate    # Run migrations
make bench      # Run benchmarks
```

## Success Metrics

- ✅ Ingestion rate: 1M+ metrics/minute
- ✅ Query latency: P95 < 100ms (hot tier)
- ✅ Storage efficiency: 90% compression
- ✅ Cost reduction: 80% vs pure TimescaleDB
- ✅ Zero data loss during migration
