-- ============================================================
-- Migration: V33 - Scheme id mismatch tracking (SCHEME-ID-MISMATCH)
-- ------------------------------------------------------------
-- Some scheme rows in the tenant master data carry a wrong
-- state_scheme_id or centre_scheme_id. A field submission carries
-- BOTH ids; the existing matching logic (state id first, then
-- centre id) resolves the reading on whichever id matches and is
-- left untouched by this migration. On top of that resolution we
-- now cross-check the OTHER submitted id against the matched
-- scheme and, when it disagrees with our stored value, record the
-- submitted (suspect) value on the scheme row so the list of
-- suspect schemes can later be handed to the client / govt dept
-- for correction. Once the master id is corrected the column is
-- nulled and the scheme drops off the export.
--
-- This migration adds the tracking columns only; the recording
-- behaviour lives in telemetry-service
-- (TelemetryTenantRepository.recordSchemeIdMismatchIfAny). Every
-- related change is marked "SCHEME-ID-MISMATCH".
--
--   scheme_master_table
--     + submitted_state_scheme_id_mismatch   VARCHAR(255)  (submitted state id that disagreed with our master)
--     + submitted_centre_scheme_id_mismatch  VARCHAR(255)  (submitted centre id that disagreed with our master)
--     + id_mismatch_last_seen_at             TIMESTAMP     (last time a new mismatching value was recorded)
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing scheme_master_table is
        -- skipped for that table only, never aborting the whole migration (the follow-up CREATE INDEX
        -- would otherwise still fail with "relation does not exist").
        IF to_regclass(format('%I.scheme_master_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.scheme_master_table
                     ADD COLUMN IF NOT EXISTS submitted_state_scheme_id_mismatch  VARCHAR(255),
                     ADD COLUMN IF NOT EXISTS submitted_centre_scheme_id_mismatch VARCHAR(255),
                     ADD COLUMN IF NOT EXISTS id_mismatch_last_seen_at            TIMESTAMP',
                tenant_schema);
            -- Partial index over only the flagged rows, backing the reconciliation export query.
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_scheme_id_mismatch
                     ON %1$I.scheme_master_table(id_mismatch_last_seen_at)
                     WHERE submitted_state_scheme_id_mismatch IS NOT NULL
                        OR submitted_centre_scheme_id_mismatch IS NOT NULL',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same columns ──────────────
-- Wrapper pattern (as used by V7/V10/V12/V31): preserve the current implementation once under a
-- versioned name, then wrap it to add the SCHEME-ID-MISMATCH columns.
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
        WHERE p.proname = 'create_tenant_schema_v33_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v33_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v33_base(schema_name);

    -- SCHEME-ID-MISMATCH: tracking columns for new tenant schemas. Guard with to_regclass so a
    -- partially-provisioned schema is skipped per-table instead of aborting on the follow-up
    -- CREATE INDEX (ALTER TABLE IF EXISTS alone is not enough — the index would still fail).
    IF to_regclass(format('%I.scheme_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.scheme_master_table
                 ADD COLUMN IF NOT EXISTS submitted_state_scheme_id_mismatch  VARCHAR(255),
                 ADD COLUMN IF NOT EXISTS submitted_centre_scheme_id_mismatch VARCHAR(255),
                 ADD COLUMN IF NOT EXISTS id_mismatch_last_seen_at            TIMESTAMP',
            schema_name);
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_scheme_id_mismatch
                 ON %1$I.scheme_master_table(id_mismatch_last_seen_at)
                 WHERE submitted_state_scheme_id_mismatch IS NOT NULL
                    OR submitted_centre_scheme_id_mismatch IS NOT NULL',
            schema_name);
    END IF;
END;
$func$;
