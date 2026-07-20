-- ============================================================
-- Migration: V31 - Lenient ingestion tracking (LENIENT-INGEST)
-- ------------------------------------------------------------
-- Allows meter-reading submissions whose scheme id or operator
-- phone is not yet present in the tenant master data to still be
-- recorded (instead of dropped), while remaining fully traceable.
--
-- This migration adds the tracking/tagging columns only; the
-- recording behaviour lives in telemetry-service and is gated by
-- the `telemetry.lenient-ingestion.enabled` flag. Every related
-- change is marked with the identifier "LENIENT-INGEST" so the
-- feature can be located and reverted once the underlying data
-- gaps are fixed.
--
--   flow_reading_table
--     + ingestion_source            SMALLINT  (bitmask, 0 = normal)
--          bit 0 (1) = UNKNOWN_SCHEME        (scheme id not in master -> placeholder scheme)
--          bit 1 (2) = UNKNOWN_OPERATOR      (phone not in user_table -> sentinel operator)
--          bit 2 (4) = OPERATOR_NOT_MAPPED   (real scheme + real operator, no mapping)
--     + submitted_state_scheme_id   VARCHAR(255)  (raw payload value, for reconciliation)
--     + submitted_centre_scheme_id  VARCHAR(255)  (raw payload value, for reconciliation)
--     + submitted_phone_hash        VARCHAR(64)   (HMAC of submitted phone, for reconciliation)
--
--   scheme_master_table
--     + is_auto_provisioned         BOOLEAN   (TRUE for placeholder schemes)
--
--   user_table
--     + is_auto_provisioned         BOOLEAN   (TRUE for the sentinel "Unknown operator")
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard every table independently with to_regclass: a partially-provisioned schema that is
        -- missing one of these tables must be skipped for that table only, never abort the whole
        -- migration. (ALTER TABLE IF EXISTS alone is not enough — the follow-up CREATE INDEX would
        -- still fail with "relation does not exist" on the missing table.)

        -- flow_reading_table tracking columns + partial index (only lenient rows are filtered here).
        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.flow_reading_table
                     ADD COLUMN IF NOT EXISTS ingestion_source           SMALLINT     NOT NULL DEFAULT 0,
                     ADD COLUMN IF NOT EXISTS submitted_state_scheme_id  VARCHAR(255),
                     ADD COLUMN IF NOT EXISTS submitted_centre_scheme_id VARCHAR(255),
                     ADD COLUMN IF NOT EXISTS submitted_phone_hash       VARCHAR(64)',
                tenant_schema);
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_flow_ingestion_source
                     ON %1$I.flow_reading_table(ingestion_source)
                     WHERE ingestion_source <> 0',
                tenant_schema);
        END IF;

        -- scheme_master_table auto-provision flag + indexes.
        IF to_regclass(format('%I.scheme_master_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.scheme_master_table
                     ADD COLUMN IF NOT EXISTS is_auto_provisioned BOOLEAN NOT NULL DEFAULT FALSE',
                tenant_schema);
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_scheme_auto_prov
                     ON %1$I.scheme_master_table(is_auto_provisioned)
                     WHERE is_auto_provisioned',
                tenant_schema);
            -- Partial UNIQUE index so exactly one placeholder scheme exists per submitted
            -- (state_scheme_id, centre_scheme_id) pair. This backs the get-or-create dedup in
            -- TelemetryTenantRepository.getOrCreatePlaceholderScheme: without it, concurrent
            -- unknown-scheme submissions would fan out duplicate placeholder schemes. Safe to build
            -- now because is_auto_provisioned was just added defaulting to FALSE (no rows match yet).
            EXECUTE format(
                'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_scheme_auto_prov_ids
                     ON %1$I.scheme_master_table(state_scheme_id, centre_scheme_id)
                     WHERE is_auto_provisioned AND deleted_at IS NULL',
                tenant_schema);
        END IF;

        -- user_table auto-provision flag (sentinel operator) + index.
        IF to_regclass(format('%I.user_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.user_table
                     ADD COLUMN IF NOT EXISTS is_auto_provisioned BOOLEAN NOT NULL DEFAULT FALSE',
                tenant_schema);
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_user_auto_prov
                     ON %1$I.user_table(is_auto_provisioned)
                     WHERE is_auto_provisioned',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same columns ──────────────
-- Wrapper pattern (as used by V7/V10/V12): preserve the current implementation
-- once under a versioned name, then wrap it to add the LENIENT-INGEST columns.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'create_tenant_schema'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'create_tenant_schema_v31_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v31_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v31_base(schema_name);

    -- LENIENT-INGEST: tracking columns for new tenant schemas. Guard each table with to_regclass so a
    -- partially-provisioned schema is skipped per-table instead of aborting on the follow-up
    -- CREATE INDEX (ALTER TABLE IF EXISTS alone is not enough — the index would still fail).
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS ingestion_source           SMALLINT     NOT NULL DEFAULT 0,
                 ADD COLUMN IF NOT EXISTS submitted_state_scheme_id  VARCHAR(255),
                 ADD COLUMN IF NOT EXISTS submitted_centre_scheme_id VARCHAR(255),
                 ADD COLUMN IF NOT EXISTS submitted_phone_hash       VARCHAR(64)',
            schema_name);
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_flow_ingestion_source
                 ON %1$I.flow_reading_table(ingestion_source)
                 WHERE ingestion_source <> 0',
            schema_name);
    END IF;

    IF to_regclass(format('%I.scheme_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.scheme_master_table
                 ADD COLUMN IF NOT EXISTS is_auto_provisioned BOOLEAN NOT NULL DEFAULT FALSE',
            schema_name);
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_scheme_auto_prov
                 ON %1$I.scheme_master_table(is_auto_provisioned)
                 WHERE is_auto_provisioned',
            schema_name);
        -- Partial UNIQUE index backing getOrCreatePlaceholderScheme dedup (see Part A for rationale).
        -- Scoped to live rows so a soft-deleted placeholder never blocks reinserting the same ids.
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_scheme_auto_prov_ids
                 ON %1$I.scheme_master_table(state_scheme_id, centre_scheme_id)
                 WHERE is_auto_provisioned AND deleted_at IS NULL',
            schema_name);
    END IF;

    IF to_regclass(format('%I.user_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.user_table
                 ADD COLUMN IF NOT EXISTS is_auto_provisioned BOOLEAN NOT NULL DEFAULT FALSE',
            schema_name);
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_user_auto_prov
                 ON %1$I.user_table(is_auto_provisioned)
                 WHERE is_auto_provisioned',
            schema_name);
    END IF;
END;
$func$;
