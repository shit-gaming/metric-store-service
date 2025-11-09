-- ======================================
-- Metric Store Database Schema
-- Includes TimescaleDB setup and indexes
-- ======================================

-- Create database if not exists
-- CREATE DATABASE metrics_db;

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "timescaledb";

-- ======================================
-- METRICS TABLE
-- ======================================
DROP TABLE IF EXISTS metric_samples CASCADE;
DROP TABLE IF EXISTS metric_labels CASCADE;
DROP TABLE IF EXISTS metrics CASCADE;

CREATE TABLE IF NOT EXISTS metrics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL CHECK (type IN ('COUNTER', 'GAUGE', 'HISTOGRAM', 'SUMMARY')),
    description TEXT,
    unit VARCHAR(100),
    retention_days INTEGER DEFAULT 30,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes for metrics table
CREATE INDEX idx_metrics_name ON metrics(name);
CREATE INDEX idx_metrics_type ON metrics(type);
CREATE INDEX idx_metrics_is_active ON metrics(is_active);
CREATE INDEX idx_metrics_name_active ON metrics(name, is_active) WHERE is_active = true;

-- ======================================
-- METRIC LABELS TABLE
-- ======================================
CREATE TABLE IF NOT EXISTS metric_labels (
    metric_id UUID NOT NULL REFERENCES metrics(id) ON DELETE CASCADE,
    label_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (metric_id, label_key)
);

-- Indexes for metric_labels table
CREATE INDEX idx_metric_labels_metric_id ON metric_labels(metric_id);

-- ======================================
-- METRIC SAMPLES TABLE (HYPERTABLE)
-- ======================================
CREATE TABLE IF NOT EXISTS metric_samples (
    time TIMESTAMPTZ NOT NULL,
    metric_id UUID NOT NULL REFERENCES metrics(id) ON DELETE CASCADE,
    value DOUBLE PRECISION NOT NULL,
    labels JSONB DEFAULT '{}'::jsonb,
    PRIMARY KEY (time, metric_id, labels)
);

-- Convert to TimescaleDB hypertable
SELECT create_hypertable('metric_samples', 'time', 
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- ======================================
-- CRITICAL INDEXES FOR QUERY PERFORMANCE
-- ======================================

-- Primary query indexes
CREATE INDEX idx_metric_samples_metric_time 
    ON metric_samples(metric_id, time DESC);

CREATE INDEX idx_metric_samples_time_metric 
    ON metric_samples(time DESC, metric_id);

-- JSONB GIN index for label queries
CREATE INDEX idx_metric_samples_labels 
    ON metric_samples USING GIN(labels);

-- Composite index for common query patterns
CREATE INDEX idx_metric_samples_metric_time_value 
    ON metric_samples(metric_id, time DESC, value);

-- Index for time-based queries
CREATE INDEX idx_metric_samples_time 
    ON metric_samples(time DESC);

-- ======================================
-- CONTINUOUS AGGREGATES (5min, 1hour, 1day)
-- ======================================

-- Drop existing continuous aggregates if they exist
DROP MATERIALIZED VIEW IF EXISTS metric_samples_5min CASCADE;
DROP MATERIALIZED VIEW IF EXISTS metric_samples_hourly CASCADE;
DROP MATERIALIZED VIEW IF EXISTS metric_samples_daily CASCADE;

-- 5-minute aggregates
CREATE MATERIALIZED VIEW metric_samples_5min
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('5 minutes', time) AS bucket,
    metric_id,
    labels,
    AVG(value) as avg_value,
    SUM(value) as sum_value,
    MIN(value) as min_value,
    MAX(value) as max_value,
    COUNT(*) as sample_count
FROM metric_samples
GROUP BY bucket, metric_id, labels
WITH NO DATA;

-- Create indexes on 5min continuous aggregate
CREATE INDEX idx_metric_samples_5min_metric_bucket 
    ON metric_samples_5min(metric_id, bucket DESC);
CREATE INDEX idx_metric_samples_5min_bucket 
    ON metric_samples_5min(bucket DESC);
CREATE INDEX idx_metric_samples_5min_labels 
    ON metric_samples_5min USING GIN(labels);

-- Hourly aggregates
CREATE MATERIALIZED VIEW metric_samples_hourly
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 hour', time) AS bucket,
    metric_id,
    labels,
    AVG(value) as avg_value,
    SUM(value) as sum_value,
    MIN(value) as min_value,
    MAX(value) as max_value,
    COUNT(*) as sample_count
FROM metric_samples
GROUP BY bucket, metric_id, labels
WITH NO DATA;

-- Create indexes on hourly continuous aggregate
CREATE INDEX idx_metric_samples_hourly_metric_bucket 
    ON metric_samples_hourly(metric_id, bucket DESC);
CREATE INDEX idx_metric_samples_hourly_bucket 
    ON metric_samples_hourly(bucket DESC);
CREATE INDEX idx_metric_samples_hourly_labels 
    ON metric_samples_hourly USING GIN(labels);

-- Daily aggregates
CREATE MATERIALIZED VIEW metric_samples_daily
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 day', time) AS bucket,
    metric_id,
    labels,
    AVG(value) as avg_value,
    SUM(value) as sum_value,
    MIN(value) as min_value,
    MAX(value) as max_value,
    COUNT(*) as sample_count
FROM metric_samples
GROUP BY bucket, metric_id, labels
WITH NO DATA;

-- Create indexes on daily continuous aggregate
CREATE INDEX idx_metric_samples_daily_metric_bucket 
    ON metric_samples_daily(metric_id, bucket DESC);
CREATE INDEX idx_metric_samples_daily_bucket 
    ON metric_samples_daily(bucket DESC);
CREATE INDEX idx_metric_samples_daily_labels 
    ON metric_samples_daily USING GIN(labels);

-- ======================================
-- REFRESH POLICIES FOR CONTINUOUS AGGREGATES
-- ======================================

-- Add refresh policy for 5-minute aggregates (refresh every 5 minutes)
SELECT add_continuous_aggregate_policy('metric_samples_5min',
    start_offset => INTERVAL '15 minutes',
    end_offset => INTERVAL '5 minutes',
    schedule_interval => INTERVAL '5 minutes',
    if_not_exists => TRUE
);

-- Add refresh policy for hourly aggregates (refresh every hour)
SELECT add_continuous_aggregate_policy('metric_samples_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE
);

-- Add refresh policy for daily aggregates (refresh every day)
SELECT add_continuous_aggregate_policy('metric_samples_daily',
    start_offset => INTERVAL '7 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- ======================================
-- DATA RETENTION POLICIES
-- ======================================

-- Add retention policy to automatically delete old data (optional)
-- Uncomment if you want automatic data cleanup
-- SELECT add_retention_policy('metric_samples', 
--     drop_after => INTERVAL '90 days',
--     if_not_exists => TRUE
-- );

-- ======================================
-- HELPER FUNCTIONS
-- ======================================

-- Function to calculate rate for counter metrics
CREATE OR REPLACE FUNCTION calculate_counter_rate(
    p_metric_id UUID,
    p_start_time TIMESTAMPTZ,
    p_end_time TIMESTAMPTZ,
    p_labels_filter JSONB DEFAULT '{}'::jsonb
) RETURNS TABLE(
    result_time TIMESTAMPTZ,
    rate_per_second DOUBLE PRECISION,
    result_labels JSONB
) AS $$
BEGIN
    RETURN QUERY
    WITH ordered_samples AS (
        SELECT 
            time,
            value,
            labels,
            LAG(value) OVER (PARTITION BY labels ORDER BY time) as prev_value,
            LAG(time) OVER (PARTITION BY labels ORDER BY time) as prev_time
        FROM metric_samples
        WHERE metric_id = p_metric_id
            AND time BETWEEN p_start_time AND p_end_time
            AND (p_labels_filter = '{}'::jsonb OR labels @> p_labels_filter)
    )
    SELECT 
        time AS result_time,
        CASE 
            WHEN prev_value IS NULL THEN 0
            WHEN value < prev_value THEN value / EXTRACT(EPOCH FROM time - prev_time)
            ELSE (value - prev_value) / EXTRACT(EPOCH FROM time - prev_time)
        END AS rate_per_second,
        labels AS result_labels
    FROM ordered_samples
    WHERE prev_time IS NOT NULL
    ORDER BY time DESC;
END;
$$ LANGUAGE plpgsql;

-- Function to calculate counter increase
CREATE OR REPLACE FUNCTION calculate_counter_increase(
    p_metric_id UUID,
    p_start_time TIMESTAMPTZ,
    p_end_time TIMESTAMPTZ,
    p_labels_filter JSONB DEFAULT '{}'::jsonb
) RETURNS TABLE(
    result_labels JSONB,
    total_increase DOUBLE PRECISION,
    start_value DOUBLE PRECISION,
    end_value DOUBLE PRECISION
) AS $$
BEGIN
    RETURN QUERY
    WITH boundary_values AS (
        SELECT DISTINCT ON (labels)
            labels,
            FIRST_VALUE(value) OVER (PARTITION BY labels ORDER BY time) as first_val,
            LAST_VALUE(value) OVER (PARTITION BY labels ORDER BY time RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) as last_val
        FROM metric_samples
        WHERE metric_id = p_metric_id
            AND time BETWEEN p_start_time AND p_end_time
            AND (p_labels_filter = '{}'::jsonb OR labels @> p_labels_filter)
    )
    SELECT 
        labels AS result_labels,
        CASE 
            WHEN last_val >= first_val THEN last_val - first_val
            ELSE last_val  -- Handle counter reset
        END AS total_increase,
        first_val AS start_value,
        last_val AS end_value
    FROM boundary_values;
END;
$$ LANGUAGE plpgsql;

-- ======================================
-- PERFORMANCE OPTIMIZATION SETTINGS
-- ======================================

-- Update table statistics for better query planning
ANALYZE metrics;
ANALYZE metric_labels;
ANALYZE metric_samples;

-- Set compression on older chunks (optional)
-- SELECT add_compression_policy('metric_samples', 
--     compress_after => INTERVAL '7 days',
--     if_not_exists => TRUE
-- );

-- ======================================
-- GRANTS (adjust as needed)
-- ======================================
-- GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO your_app_user;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO your_app_user;

-- ======================================
-- COLD STORAGE METADATA TABLE
-- ======================================
DROP TABLE IF EXISTS cold_storage_metadata CASCADE;

CREATE TABLE IF NOT EXISTS cold_storage_metadata (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    metric_id UUID NOT NULL REFERENCES metrics(id) ON DELETE CASCADE,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    row_count BIGINT NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    file_format VARCHAR(50) DEFAULT 'json',
    compression_ratio DECIMAL(5,4),
    labels_index JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(metric_id, start_time, end_time)
);

-- Create indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_cold_storage_metric_time ON cold_storage_metadata(metric_id, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_cold_storage_created ON cold_storage_metadata(created_at);
CREATE INDEX IF NOT EXISTS idx_cold_storage_metric_id ON cold_storage_metadata(metric_id);

-- ======================================
-- INITIAL DATA REFRESH
-- ======================================
-- Refresh all continuous aggregates with initial data
CALL refresh_continuous_aggregate('metric_samples_5min', NULL, NULL);
CALL refresh_continuous_aggregate('metric_samples_hourly', NULL, NULL);
CALL refresh_continuous_aggregate('metric_samples_daily', NULL, NULL);

-- ======================================
-- VERIFICATION QUERIES
-- ======================================
-- Check hypertable chunks
-- SELECT * FROM timescaledb_information.chunks WHERE hypertable_name = 'metric_samples';

-- Check continuous aggregates
-- SELECT * FROM timescaledb_information.continuous_aggregates;

-- Check indexes
-- SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY tablename, indexname;
