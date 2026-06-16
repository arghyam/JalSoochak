-- ============================================================
-- AGG REGION METRICS (pre-rolled region KPIs per period bucket)
-- ============================================================
-- One row per (period_scale, period_start, hierarchy, region_level, region_id).
-- Pre-rolled at every node of both hierarchies (LGD/department, levels 1..6) so
-- a dashboard card is a single indexed lookup. period_scale in DAY/WEEK/MONTH;
-- weeks run Sunday->Saturday, months are calendar-aligned.
--
-- Additive measures may be summed across day rows for an arbitrary range. The
-- non-additive measures (continuous/critical/distinct) are a per-bucket
-- convenience only and must NOT be summed across buckets — derive those from
-- agg_scheme_daily for multi-bucket spans.
--
-- NOTE: kept as a plain table for v1; range-partitioning by period_start is a
-- later optimization if row volume requires it.
-- ============================================================

CREATE TABLE IF NOT EXISTS analytics_schema.agg_region_metrics (
    id                              BIGSERIAL  PRIMARY KEY,
    period_scale                    VARCHAR(8) NOT NULL,   -- DAY | WEEK | MONTH
    period_start                    DATE       NOT NULL,
    period_end                      DATE       NOT NULL,
    tenant_id                       INT        NOT NULL,
    hierarchy                       VARCHAR(8) NOT NULL,   -- LGD | DEPT
    region_level                    SMALLINT   NOT NULL,   -- 1..6
    region_id                       INT        NOT NULL,

    -- additive measures (safe to sum across days / nodes)
    days_in_range                   INT        NOT NULL DEFAULT 0,
    scheme_count                    INT        NOT NULL DEFAULT 0,
    total_supply_days               INT        NOT NULL DEFAULT 0,
    total_submission_days           INT        NOT NULL DEFAULT 0,
    active_scheme_count             INT        NOT NULL DEFAULT 0,
    inactive_scheme_count           INT        NOT NULL DEFAULT 0,
    total_water_supplied_liters     BIGINT     NOT NULL DEFAULT 0,  -- SUM(water_quantity) all rows (region-wise water qty)
    total_water_submitted_liters    BIGINT     NOT NULL DEFAULT 0,  -- SUM(submitted water_quantity) for national water supply
    water_quantity_row_count        BIGINT     NOT NULL DEFAULT 0,  -- divisor for averageWaterQuantity
    total_confirmed_reading         BIGINT     NOT NULL DEFAULT 0,  -- SUM(confirmed_reading>0) for periodic regularity
    total_household_count           BIGINT     NOT NULL DEFAULT 0,
    total_achieved_fhtc             BIGINT     NOT NULL DEFAULT 0,
    total_planned_fhtc              BIGINT     NOT NULL DEFAULT 0,
    supply_days_in_efficient_range  INT        NOT NULL DEFAULT 0,
    compliant_submission_count      INT        NOT NULL DEFAULT 0,
    anomalous_submission_count      INT        NOT NULL DEFAULT 0,

    -- non-additive per-bucket convenience (NOT summable across buckets)
    continuous_scheme_count         INT        NOT NULL DEFAULT 0,
    critical_scheme_count           INT        NOT NULL DEFAULT 0,
    distinct_submitting_schemes     INT        NOT NULL DEFAULT 0,

    -- derived ratios for this exact bucket
    average_regularity              NUMERIC(6,2),
    reading_submission_rate         NUMERIC(6,2),
    avg_water_supply_per_scheme     NUMERIC(18,2),

    -- norm snapshot used for this region/period
    snap_required_lpcd              INT,
    snap_persons_per_hh             INT,
    snap_over_pct                   INT,
    snap_under_pct                  INT,

    computed_at                     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_final                        BOOLEAN    NOT NULL DEFAULT FALSE,

    -- region_id (lgd/department id) is only unique within a tenant, so tenant_id
    -- is part of the natural key.
    CONSTRAINT uq_agg_region_metrics
        UNIQUE (period_scale, period_start, tenant_id, hierarchy, region_level, region_id)
);

CREATE INDEX IF NOT EXISTS idx_agg_region_metrics_lookup
    ON analytics_schema.agg_region_metrics(period_scale, tenant_id, hierarchy, region_level, region_id, period_start);
CREATE INDEX IF NOT EXISTS idx_agg_region_metrics_tenant
    ON analytics_schema.agg_region_metrics(tenant_id, period_scale, period_start);
