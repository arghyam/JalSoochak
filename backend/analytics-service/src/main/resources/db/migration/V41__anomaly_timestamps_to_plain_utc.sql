-- ============================================================
-- V41 - anomaly_table timestamps -> plain TIMESTAMP (UTC)   (REPORTED-METRIC)
-- ------------------------------------------------------------
-- analytics_schema.anomaly_table was the one table storing timestamps as TIMESTAMPTZ
-- (V17), unlike every other analytics/tenant table which uses plain TIMESTAMP holding
-- UTC. That made "created_at + INTERVAL '5:30'" (used to derive the IST reporting day
-- for the "reported" continuity KPIs) depend on the DB session timezone — correct only
-- on a UTC session, wrong on Asia/Kolkata (double-shift).
--
-- This converts the columns to plain TIMESTAMP holding the UTC wall-clock, so "+ 5:30"
-- is session-independent like the rest of the repo. The "USING ... AT TIME ZONE 'UTC'"
-- is explicit, so the converted values do NOT depend on the session timezone at run time.
--
-- Idempotent: only alters while created_at is still timestamptz (safe to re-run / if a
-- column was already fixed).
-- ============================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'analytics_schema'
          AND table_name   = 'anomaly_table'
          AND column_name  = 'created_at'
          AND data_type    = 'timestamp with time zone'
    ) THEN
        ALTER TABLE analytics_schema.anomaly_table
            ALTER COLUMN created_at  TYPE timestamp USING created_at  AT TIME ZONE 'UTC',
            ALTER COLUMN updated_at  TYPE timestamp USING updated_at  AT TIME ZONE 'UTC',
            ALTER COLUMN resolved_at TYPE timestamp USING resolved_at AT TIME ZONE 'UTC',
            ALTER COLUMN deleted_at  TYPE timestamp USING deleted_at  AT TIME ZONE 'UTC';
    END IF;
END $$;
