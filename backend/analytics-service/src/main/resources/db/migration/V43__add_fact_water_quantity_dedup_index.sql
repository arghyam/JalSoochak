-- Backs the LATEST_WATER_QUANTITY DISTINCT ON de-duplication in SchemeRegularityRepository:
--   DISTINCT ON (tenant_id, scheme_id, date) ... ORDER BY tenant_id, scheme_id, date, updated_at DESC, id DESC
-- A btree matching that ordering lets Postgres pick the latest row per (tenant_id, scheme_id, date)
-- via an index scan instead of a full-table sort. Existing indexes on this table are single-column
-- (tenant_id / scheme_id / date) and cannot serve the composite ordering.
--
-- NOTE: built with a plain (transactional) CREATE INDEX, consistent with every other index migration in
-- this service (e.g. V37). CREATE INDEX CONCURRENTLY was evaluated but rejected: it cannot run inside a
-- transaction and hangs under Flyway's pooled-connection model (it waits forever on the pool's other open
-- snapshot), which stalls the migration. If this table is large enough for the build lock to matter at
-- deploy time, pre-create the index CONCURRENTLY out-of-band before deploying; the IF NOT EXISTS below
-- then makes this migration a no-op.

CREATE INDEX IF NOT EXISTS idx_fact_water_dedup
    ON analytics_schema.fact_water_quantity_table (tenant_id, scheme_id, date, updated_at DESC, id DESC);
