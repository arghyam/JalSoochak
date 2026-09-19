-- ============================================================
-- V48 - Link an anomaly to the submission that caused it   (ANOMALY-SUBMISSION-LINK)
-- ------------------------------------------------------------
-- The tenant-schema half of this change (backend/database/V43) puts a real
-- flow_reading_id FK on tenant_<x>.anomaly_table. That id is schema-local and
-- meaningless inside analytics_schema, so the warehouse is linked on the
-- submission's correlation id instead — which requires a column on BOTH
-- sides, because neither had one that means this:
--
--   anomaly_table
--     + submission_correlation_id  TEXT   -- the flow_reading_table.correlation_id
--                                         -- of the submission that caused it
--   fact_meter_reading_table
--     + correlation_id             TEXT   -- the same value, carried on the
--                                         -- METER_READING_RECORDED event
--
-- Why a NEW column on anomaly_table rather than reusing correlation_id:
-- anomaly_table.correlation_id is NOT a submission pointer. It is a dedup
-- hash, built as UUID(type:userId:schemeId:readingUrl) for image anomalies
-- and UUID(type:userId:schemeId:readingDate) for supply anomalies, and
-- anomaly_table.uuid is DERIVED from it by
-- TelemetryEventPublisher.resolveAnomalyEventUuid(). The UNIQUE constraint on
-- uuid is what implements "touch, don't insert" for a repeat. Repointing that
-- column at the submission would silently change the dedup key and turn every
-- repeat image submission in a day into its own row. The two ids answer
-- different questions and both are kept.
--
-- Note the asymmetry that follows from the source data: flow_reading_table's
-- correlation_id has no unique constraint and is deliberately shared across
-- rows by the Glific flows, so this join is a drill-down/trace, NOT a
-- counting key. Counting is what the tenant-side flow_reading_id FK is for.
--
-- Both columns are nullable. The five anomaly types with no submission behind
-- them (1, 3, 4, 6, 9) leave submission_correlation_id NULL, and every fact
-- row written before this migration has no correlation id to backfill from —
-- the value exists only in the tenant schema the event came from, and the
-- events are not replayed.
-- ============================================================

ALTER TABLE analytics_schema.anomaly_table
    ADD COLUMN IF NOT EXISTS submission_correlation_id TEXT;

ALTER TABLE analytics_schema.fact_meter_reading_table
    ADD COLUMN IF NOT EXISTS correlation_id TEXT;

-- Partial indexes: both joins only ever ask about linked rows, and every
-- pre-existing row on either table keeps NULL here.
CREATE INDEX IF NOT EXISTS idx_anomaly_submission_corr
    ON analytics_schema.anomaly_table (submission_correlation_id)
    WHERE submission_correlation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fact_meter_reading_corr
    ON analytics_schema.fact_meter_reading_table (correlation_id)
    WHERE correlation_id IS NOT NULL;
