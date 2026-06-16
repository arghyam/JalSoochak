-- ============================================================
-- AGG SCHEME DAILY (base summary fact: scheme x calendar day)
-- ============================================================
-- Lowest-grain pre-aggregation, midnight->midnight. Carries the LGD and
-- department ancestor keys (copied from dim_scheme_table) so region rollups need
-- no join, and snapshots the water-norm values actually used that day. This is
-- the authority for non-additive KPIs (continuous / critical / distinct schemes)
-- over arbitrary ranges.
-- ============================================================

CREATE TABLE IF NOT EXISTS analytics_schema.agg_scheme_daily (
    scheme_id                  INT       NOT NULL,
    reading_date               DATE      NOT NULL,
    tenant_id                  INT       NOT NULL,

    level_1_lgd_id             INT,
    level_2_lgd_id             INT,
    level_3_lgd_id             INT,
    level_4_lgd_id             INT,
    level_5_lgd_id             INT,
    level_6_lgd_id             INT,
    level_1_dept_id            INT,
    level_2_dept_id            INT,
    level_3_dept_id            INT,
    level_4_dept_id            INT,
    level_5_dept_id            INT,
    level_6_dept_id            INT,

    submitted                  SMALLINT  NOT NULL DEFAULT 0,  -- reading submitted that day (0/1)
    supplied                   SMALLINT  NOT NULL DEFAULT 0,  -- confirmed_reading > 0 (0/1)
    water_quantity_liters      BIGINT    NOT NULL DEFAULT 0,  -- SUM(fact_water_quantity.water_quantity) (all rows)
    water_quantity_submitted_liters BIGINT NOT NULL DEFAULT 0, -- SUM(water_quantity WHERE submission_status IN (1,NULL) AND >0) - national water supply
    water_quantity_row_count   INT       NOT NULL DEFAULT 0,  -- COUNT(fact_water_quantity rows) - divisor for averages
    confirmed_reading_total    BIGINT    NOT NULL DEFAULT 0,  -- SUM(confirmed_reading WHERE > 0) - periodic regularity water qty
    compliant_count            INT       NOT NULL DEFAULT 0,
    anomalous_count            INT       NOT NULL DEFAULT 0,
    household_count            INT       NOT NULL DEFAULT 0,
    achieved_fhtc_count        INT       NOT NULL DEFAULT 0,
    planned_fhtc_count         INT       NOT NULL DEFAULT 0,
    in_efficient_range         SMALLINT  NOT NULL DEFAULT 0,  -- supply within [under,over] band (0/1)

    outage_reason_code         VARCHAR(64),
    non_submission_reason_code VARCHAR(64),
    scheme_status_code         VARCHAR(32),

    -- norm snapshot actually used for this scheme/day
    snap_required_lpcd         INT,
    snap_persons_per_hh        INT,
    snap_over_pct              INT,
    snap_under_pct             INT,

    computed_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_final                   BOOLEAN   NOT NULL DEFAULT FALSE,

    -- scheme_id is only unique within a tenant, so the grain key includes tenant_id.
    CONSTRAINT pk_agg_scheme_daily PRIMARY KEY (tenant_id, scheme_id, reading_date)
);

CREATE INDEX IF NOT EXISTS idx_agg_scheme_daily_tenant_date
    ON analytics_schema.agg_scheme_daily(tenant_id, reading_date);
CREATE INDEX IF NOT EXISTS idx_agg_scheme_daily_date
    ON analytics_schema.agg_scheme_daily(reading_date);
-- Common dashboard rollup levels (district / block / gram-panchayat).
CREATE INDEX IF NOT EXISTS idx_agg_scheme_daily_lgd2_date
    ON analytics_schema.agg_scheme_daily(level_2_lgd_id, reading_date);
CREATE INDEX IF NOT EXISTS idx_agg_scheme_daily_lgd3_date
    ON analytics_schema.agg_scheme_daily(level_3_lgd_id, reading_date);
CREATE INDEX IF NOT EXISTS idx_agg_scheme_daily_lgd4_date
    ON analytics_schema.agg_scheme_daily(level_4_lgd_id, reading_date);
