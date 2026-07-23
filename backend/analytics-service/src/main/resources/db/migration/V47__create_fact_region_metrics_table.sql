-- ============================================================
-- FACT REGION METRICS (pre-rolled region KPIs per period bucket)
-- ============================================================
-- One row per (period_scale, period_start, hierarchy, region_level, region_id).
-- Pre-rolled at every node of both hierarchies (LGD/department, levels 1..6) so
-- a dashboard card is a single indexed lookup. period_scale in DAY/WEEK/MONTH;
-- weeks run Sunday->Saturday, months are calendar-aligned.
--
-- Additive measures may be summed across day rows for an arbitrary range. The
-- non-additive measures (continuous/critical/distinct) are a per-bucket
-- convenience only and must NOT be summed across buckets — derive those from
-- fact_scheme_daily_table for multi-bucket spans.
--
-- ONE water figure: total_water_supplied_liters = SUM(water_supplied_liters).
-- The national and region-wise cards read this same column; average water per
-- supply day = total_water_supplied_liters / total_supply_days (after the
-- de-dup a scheme-day contributes at most one qualifying water row).
--
-- work_status_scope: rows exist per filter policy, because tenant screens and the
-- national dashboard intentionally judge schemes against different work_status
-- filter tiers (own tenant → national → env vs national → env):
--   TENANT   = built with the scheme's own-tenant filter chain (all hierarchies/levels)
--   NATIONAL = built with the uniform national chain (LGD levels 1-2 only — all the
--              national dashboard reads)
-- The filter applied is the SCD-2 history row (dim_tenant_work_status_filter_table)
-- in force on the bucket's period_end, so stored history remains reproducible when
-- the filter changes later.
--
-- NOTE: kept as a plain table for v1; range-partitioning by period_start is a
-- later optimization if row volume requires it.
-- ============================================================

CREATE TABLE analytics_schema.fact_region_metrics_table (
    id                              BIGSERIAL  PRIMARY KEY,
    period_scale                    VARCHAR(8) NOT NULL,   -- DAY | WEEK | MONTH
    period_start                    DATE       NOT NULL,
    period_end                      DATE       NOT NULL,
    tenant_id                       INT        NOT NULL,
    hierarchy                       VARCHAR(8) NOT NULL,   -- LGD | DEPT
    region_level                    SMALLINT   NOT NULL,   -- 1..6
    region_id                       INT        NOT NULL,
    work_status_scope               VARCHAR(8) NOT NULL DEFAULT 'TENANT',  -- TENANT | NATIONAL

    -- additive measures (safe to sum across days / nodes)
    days_in_range                   INT        NOT NULL DEFAULT 0,
    scheme_count                    INT        NOT NULL DEFAULT 0,
    total_supply_days               INT        NOT NULL DEFAULT 0,
    total_submission_days           INT        NOT NULL DEFAULT 0,
    active_scheme_count             INT        NOT NULL DEFAULT 0,
    inactive_scheme_count           INT        NOT NULL DEFAULT 0,
    total_water_supplied_liters     BIGINT     NOT NULL DEFAULT 0,  -- SUM(water_supplied_liters) — the only water total
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

    -- NO derived-ratio columns are stored. Ratios (regularity %, submission rate, water
    -- per scheme) are always recomputed on read from the additive numerators/denominators
    -- above, for the exact grouping requested — averaging pre-averaged rows is unsafe
    -- (Simpson's paradox) when buckets/regions differ in size.

    -- norm snapshot used for this region/period
    norm_required_lpcd              INT,
    norm_persons_per_household      INT,
    norm_over_supply_pct            INT,
    norm_under_supply_pct           INT,

    computed_at                     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_final                        BOOLEAN    NOT NULL DEFAULT FALSE,

    -- region_id (lgd/department id) is only unique within a tenant, so tenant_id
    -- is part of the natural key; one row per filter scope.
    CONSTRAINT uq_fact_region_metrics
        UNIQUE (period_scale, period_start, tenant_id, hierarchy, region_level, region_id, work_status_scope)
);

CREATE INDEX IF NOT EXISTS idx_fact_region_metrics_lookup
    ON analytics_schema.fact_region_metrics_table(period_scale, tenant_id, hierarchy, region_level, region_id, period_start, work_status_scope);
CREATE INDEX IF NOT EXISTS idx_fact_region_metrics_tenant
    ON analytics_schema.fact_region_metrics_table(tenant_id, period_scale, period_start);
