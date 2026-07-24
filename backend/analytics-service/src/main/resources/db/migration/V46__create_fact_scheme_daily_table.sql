-- ============================================================
-- FACT SCHEME DAILY (base summary fact: scheme x calendar day)
-- ============================================================
-- Lowest-grain pre-aggregation, midnight->midnight. Carries the LGD and
-- department ancestor keys (copied from dim_scheme_table) so region rollups need
-- no join, and snapshots the water-norm values actually used that day. This is
-- the authority for non-additive KPIs (continuous / critical / distinct schemes)
-- over arbitrary ranges.
--
-- ONE water figure: water_supplied_liters follows the canonical supplied-water
-- rule shared by every dashboard KPI (latest fact_water_quantity row per
-- scheme/day; submission_status = 1 (SUBMITTED) or legacy NULL; quantity > 0).
-- The national and region-wise cards read this same column, so the two figures
-- cannot diverge. After de-duplication a scheme-day has at most one qualifying
-- water row, so `supplied` doubles as the row count / average divisor.
-- ============================================================

CREATE TABLE analytics_schema.fact_scheme_daily_table (
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

    submitted                  SMALLINT  NOT NULL DEFAULT 0,  -- meter reading sent that day (0/1)
    supplied                   SMALLINT  NOT NULL DEFAULT 0,  -- day's water row qualifies under the supplied-water rule (0/1)
    water_supplied_liters      BIGINT    NOT NULL DEFAULT 0,  -- THE single water figure (supplied-water rule)
    compliant_count            INT       NOT NULL DEFAULT 0,
    anomalous_count            INT       NOT NULL DEFAULT 0,
    household_count            INT       NOT NULL DEFAULT 0,
    achieved_fhtc_count        INT       NOT NULL DEFAULT 0,
    planned_fhtc_count         INT       NOT NULL DEFAULT 0,
    is_supply_efficient        SMALLINT  NOT NULL DEFAULT 0,  -- water_supplied_liters within [under,over] band (0/1)

    outage_reason_code         VARCHAR(64),
    non_submission_reason_code VARCHAR(64),
    scheme_status_code         VARCHAR(32),

    -- norm snapshot actually used for this scheme/day
    norm_required_lpcd         INT,
    norm_persons_per_household INT,
    norm_over_supply_pct       INT,
    norm_under_supply_pct      INT,

    computed_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_final                   BOOLEAN   NOT NULL DEFAULT FALSE,

    -- scheme_id is only unique within a tenant, so the grain key includes tenant_id.
    CONSTRAINT pk_fact_scheme_daily PRIMARY KEY (tenant_id, scheme_id, reading_date)
);

CREATE INDEX IF NOT EXISTS idx_fact_scheme_daily_tenant_date
    ON analytics_schema.fact_scheme_daily_table(tenant_id, reading_date);
CREATE INDEX IF NOT EXISTS idx_fact_scheme_daily_date
    ON analytics_schema.fact_scheme_daily_table(reading_date);
-- Common dashboard rollup levels (district / block / gram-panchayat).
CREATE INDEX IF NOT EXISTS idx_fact_scheme_daily_lgd2_date
    ON analytics_schema.fact_scheme_daily_table(level_2_lgd_id, reading_date);
CREATE INDEX IF NOT EXISTS idx_fact_scheme_daily_lgd3_date
    ON analytics_schema.fact_scheme_daily_table(level_3_lgd_id, reading_date);
CREATE INDEX IF NOT EXISTS idx_fact_scheme_daily_lgd4_date
    ON analytics_schema.fact_scheme_daily_table(level_4_lgd_id, reading_date);
