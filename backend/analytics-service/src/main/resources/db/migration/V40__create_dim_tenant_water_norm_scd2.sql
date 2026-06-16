-- ============================================================
-- DIM TENANT WATER NORM (SCD TYPE 2 — effective-dated history)
-- ============================================================
-- Source of truth for the water-norm values used in KPI calculations.
-- dim_tenant_table keeps the *current* convenience copy; this table keeps the
-- full timeline so historical aggregates remain reproducible.
--
-- Intervals are half-open: a row applies for dates d where
--   effective_from <= d AND (effective_to IS NULL OR d < effective_to)
-- effective_to IS NULL marks the single currently-in-effect row per tenant.
-- ============================================================

CREATE TABLE IF NOT EXISTS analytics_schema.dim_tenant_water_norm (
    id                            BIGSERIAL    PRIMARY KEY,
    tenant_id                     INT          NOT NULL
                                      REFERENCES analytics_schema.dim_tenant_table(tenant_id),
    effective_from                DATE         NOT NULL,
    effective_to                  DATE,                       -- NULL = current
    required_lpcd                 INT,
    person_count_per_household    INT,
    over_supply_range_percentage  INT,
    under_supply_range_percentage INT,
    created_at                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- At most one open (current) row per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_dim_tenant_water_norm_open
    ON analytics_schema.dim_tenant_water_norm(tenant_id)
    WHERE effective_to IS NULL;

-- Point-in-time lookup by tenant + date.
CREATE INDEX IF NOT EXISTS idx_dim_tenant_water_norm_lookup
    ON analytics_schema.dim_tenant_water_norm(tenant_id, effective_from, effective_to);

-- ------------------------------------------------------------
-- Seed one open row per existing tenant from the current norms.
-- effective_from = tenant creation date (norms have applied since the tenant
-- existed); only insert when the tenant has no open row yet (idempotent).
-- ------------------------------------------------------------
INSERT INTO analytics_schema.dim_tenant_water_norm
    (tenant_id, effective_from, effective_to, required_lpcd,
     person_count_per_household, over_supply_range_percentage,
     under_supply_range_percentage, created_at)
SELECT t.tenant_id,
       COALESCE(t.created_at::date, CURRENT_DATE),
       NULL,
       t.required_lpcd,
       COALESCE(t.person_count_per_household, 5),
       t.over_supply_range_percentage,
       t.under_supply_range_percentage,
       CURRENT_TIMESTAMP
FROM analytics_schema.dim_tenant_table t
WHERE NOT EXISTS (
    SELECT 1
    FROM analytics_schema.dim_tenant_water_norm n
    WHERE n.tenant_id = t.tenant_id
      AND n.effective_to IS NULL
);
