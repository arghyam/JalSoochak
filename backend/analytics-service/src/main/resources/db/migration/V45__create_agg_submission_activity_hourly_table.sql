-- ============================================================
-- AGG SUBMISSION ACTIVITY HOURLY (HOUR grain — counts only)
-- ============================================================
-- The only sub-daily aggregate. Captures *when readings arrive* using
-- fact_meter_reading_table.reading_at. Supply / regularity / quantity are
-- inherently daily and are NOT stored at hour grain.
-- ============================================================

CREATE TABLE analytics_schema.agg_submission_activity_hourly (
    id                     BIGSERIAL  PRIMARY KEY,
    hour_start             TIMESTAMP  NOT NULL,   -- truncated to the hour
    tenant_id              INT        NOT NULL,
    hierarchy              VARCHAR(8) NOT NULL DEFAULT 'LGD',
    region_level           SMALLINT   NOT NULL DEFAULT 1,
    region_id              INT        NOT NULL,
    submission_count       INT        NOT NULL DEFAULT 0,
    distinct_scheme_count  INT        NOT NULL DEFAULT 0,
    computed_at            TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_agg_submission_activity_hourly
        UNIQUE (hour_start, tenant_id, hierarchy, region_level, region_id)
);

CREATE INDEX IF NOT EXISTS idx_agg_submission_activity_hourly_lookup
    ON analytics_schema.agg_submission_activity_hourly(tenant_id, hour_start);
