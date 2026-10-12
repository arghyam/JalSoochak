-- ============================================================
-- V57 - One performance score per (tenant, scheme, day)
-- ------------------------------------------------------------
-- SchemePerformanceScoreTask runs at midnight on every analytics pod. Its INSERT ... WHERE NOT EXISTS
-- could not see another pod's uncommitted rows, so pods running together wrote the same day twice.
-- With this key the insert uses ON CONFLICT DO NOTHING instead (SchemePerformanceSchedulerRepository):
-- a second run waits on the first one's rows, then skips them.
--
-- A unique index rather than ADD CONSTRAINT, as V56 does for uq_fact_meter_reading_source: ON CONFLICT
-- infers it, IF NOT EXISTS keeps this re-runnable, and the build blocks writes but not dashboard reads.
-- ADD CONSTRAINT would take an ACCESS EXCLUSIVE lock and block reads too.
--
-- last_water_supply_date is nullable. NULLs never conflict in a unique index, and the DELETE leaves
-- them alone to match.
--
-- NOTE: plain (transactional) CREATE INDEX, as in V43 and V56: CONCURRENTLY hangs under Flyway inside
-- Spring Boot. Flyway runs the DELETE and the index build in one transaction, so a failure leaves the
-- table as it was.
-- ============================================================

-- Two pods ran the midnight insert together, so some (tenant, scheme, date) rows are doubled.
-- Both copies were computed from the same data; keep the first.
DELETE FROM analytics_schema.fact_scheme_performance_table a
USING analytics_schema.fact_scheme_performance_table b
WHERE a.tenant_id = b.tenant_id AND a.scheme_id = b.scheme_id
  AND a.last_water_supply_date = b.last_water_supply_date AND a.id > b.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_fact_scheme_performance
    ON analytics_schema.fact_scheme_performance_table (tenant_id, scheme_id, last_water_supply_date);
