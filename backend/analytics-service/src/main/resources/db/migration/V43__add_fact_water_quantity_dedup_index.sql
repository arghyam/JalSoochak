-- Backs the LATEST_WATER_QUANTITY DISTINCT ON de-duplication in SchemeRegularityRepository:
--   DISTINCT ON (tenant_id, scheme_id, date) ... ORDER BY tenant_id, scheme_id, date, updated_at DESC, id DESC
-- A btree matching that ordering lets Postgres pick the latest row per (tenant_id, scheme_id, date)
-- via an index scan instead of a full-table sort. Existing indexes on this table are single-column
-- (tenant_id / scheme_id / date) and cannot serve the composite ordering.

CREATE INDEX IF NOT EXISTS idx_fact_water_dedup
    ON analytics_schema.fact_water_quantity_table (tenant_id, scheme_id, date, updated_at DESC, id DESC);
