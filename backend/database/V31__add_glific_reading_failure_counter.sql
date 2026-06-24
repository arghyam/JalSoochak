-- Tracks consecutive failed image-reading submissions from Glific.
-- Keying by date gives an automatic daily reset while keeping increments atomic
-- through the primary-key upsert used by telemetry-service.
-- telemetry-service deletes rows older than the configured retention period.

CREATE TABLE IF NOT EXISTS common_schema.glific_reading_failure_counter (
    contact_id                  TEXT        NOT NULL,
    failure_date                DATE        NOT NULL,
    consecutive_failure_count   INTEGER     NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (contact_id, failure_date)
);

CREATE INDEX IF NOT EXISTS idx_glific_reading_failure_counter_date
    ON common_schema.glific_reading_failure_counter (failure_date);
