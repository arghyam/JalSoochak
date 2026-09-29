-- ============================================================
-- DIM TENANT WORK STATUS FILTER (SCD TYPE 2 — effective-dated history)
-- ============================================================
-- History of the dashboard work_status filter (included_work_statuses) per tier:
--   tenant_id > 0  = a tenant's own filter
--   tenant_id = 0  = the national default
-- dim_tenant_table.included_work_statuses keeps the *current* convenience copy;
-- this table keeps the full timeline so pre-aggregated KPIs are built with the
-- filter that was in force for the period being aggregated (same reproducibility
-- contract as dim_tenant_water_norm_table).
--
-- Intervals are half-open: a row applies for dates d where
--   effective_from <= d AND (effective_to IS NULL OR d < effective_to)
-- effective_to IS NULL marks the single currently-in-effect row per tenant.
--
-- No FK to dim_tenant_table: tenant_id = 0 is a config-only sentinel that has no
-- guaranteed dimension row (it is materialised lazily on the first national
-- config event).
-- ============================================================

CREATE TABLE IF NOT EXISTS analytics_schema.dim_tenant_work_status_filter_table (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              INT       NOT NULL,
    effective_from         DATE      NOT NULL,
    effective_to           DATE,                    -- NULL = current
    included_work_statuses INT[],                   -- NULL/empty = tier not configured (falls through)
    created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- At most one open (current) row per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_dim_tenant_work_status_filter_open
    ON analytics_schema.dim_tenant_work_status_filter_table(tenant_id)
    WHERE effective_to IS NULL;

-- Point-in-time lookup by tenant + date.
CREATE INDEX IF NOT EXISTS idx_dim_tenant_work_status_filter_lookup
    ON analytics_schema.dim_tenant_work_status_filter_table(tenant_id, effective_from, effective_to);

-- ------------------------------------------------------------
-- Seed one open row per tenant that has a configured filter today (including a
-- tenant-0 national-default row when present). effective_from = tenant creation
-- date, mirroring the water-norm seed: treating the current filter as
-- always-in-force reproduces the legacy read-time (retroactive) behaviour for
-- historical backfills. Idempotent: only inserts when no open row exists.
-- ------------------------------------------------------------
INSERT INTO analytics_schema.dim_tenant_work_status_filter_table
    (tenant_id, effective_from, effective_to, included_work_statuses, created_at)
SELECT t.tenant_id,
       COALESCE(t.created_at::date, CURRENT_DATE),
       NULL,
       t.included_work_statuses,
       CURRENT_TIMESTAMP
FROM analytics_schema.dim_tenant_table t
WHERE t.included_work_statuses IS NOT NULL
  AND t.included_work_statuses <> '{}'::int[]
  AND NOT EXISTS (
      SELECT 1
      FROM analytics_schema.dim_tenant_work_status_filter_table f
      WHERE f.tenant_id = t.tenant_id
        AND f.effective_to IS NULL
  );
