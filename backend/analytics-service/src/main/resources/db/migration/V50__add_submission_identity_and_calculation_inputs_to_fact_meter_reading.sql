-- ============================================================
-- V50 - One fact row per submission, and the inputs to recalculate it
-- ------------------------------------------------------------
-- fact_meter_reading_table
--   + source_reading_id       BIGINT     -- the tenant's flow_reading_table.id
--   + source_updated_at       TIMESTAMP  -- that row's updated_at, the event's version
--   + calculation_parameters  JSONB      -- telemetry's pump/formula snapshot (ELM and PDU only)
--
-- A re-published submission (a correction) updates its own row instead of adding one, and only
-- when its version is at least the stored one; see FactMeterReadingRepositoryCustomImpl.upsert.
-- correlation_id cannot be that key: the WhatsApp flows share it across rows (V48).
--
-- All three are nullable. Rows written before this migration, and events from a telemetry-service
-- that does not send them yet, have no source id and are inserted as before. NULLs never conflict
-- in a unique index, so the partial index below only ever constrains identified rows.
--
-- The snapshot is stored because analytics recalculates days outside the event that triggered them
-- (the next-day follow-up, PDU day totals) and cannot read tenant schemas itself.
--
-- The lookup index serves every per-scheme reading query water-quantity recalculation makes: the
-- latest reading on a date, the starting point before it, and the next date with a reading. Its
-- name and definition are those scripts/water_quantity_units_fix.py already builds, so a database
-- where that script ran keeps its index and this statement does nothing. Backward scans make the
-- DESC columns serve ascending lookups too. channel is left out on purpose:
-- COALESCE(channel, 1) = :channel cannot use a plain index column, and a day holds only a few rows.
--
-- NOTE: plain (transactional) CREATE INDEX, as in V43: CONCURRENTLY hangs under Flyway inside Spring
-- Boot. If the table is large enough for the build lock to matter, pre-create both indexes
-- CONCURRENTLY out-of-band before deploying; IF NOT EXISTS then makes this a no-op.
-- ============================================================

ALTER TABLE analytics_schema.fact_meter_reading_table
    ADD COLUMN IF NOT EXISTS source_reading_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS calculation_parameters JSONB;

CREATE UNIQUE INDEX IF NOT EXISTS uq_fact_meter_reading_source
    ON analytics_schema.fact_meter_reading_table (tenant_id, source_reading_id)
    WHERE source_reading_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fact_meter_reading_tenant_scheme_date_lookup
    ON analytics_schema.fact_meter_reading_table
    (tenant_id, scheme_id, reading_date DESC, reading_at DESC, id DESC);
