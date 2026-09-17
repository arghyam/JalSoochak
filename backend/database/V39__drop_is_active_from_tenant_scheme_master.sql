-- ============================================================
-- Migration: V39 - Drop scheme_master_table.is_active
-- ------------------------------------------------------------
-- V29 added is_active to every tenant's scheme_master_table, and a nightly
-- job in scheme-service (SchemeActivitySyncScheduler) rewrote it from recent
-- flow_reading_table activity. Nothing ever read the column: no query, no DTO,
-- no export, no screen. It was a second, disagreeing answer to a question the
-- schemes' real columns already answer.
--
--   work_status       1 Ongoing, 2 Completed, 3 Not Started, 4 Handed Over
--   operating_status  0 Non-Operative, 1 Operative, 2 Partially Operative
--
-- "Has not reported in N days" — the thing is_active actually measured — is
-- served live by GET /analytics/critical-schemes, which stores nothing.
--
--   scheme_master_table
--     - is_active  BOOLEAN NOT NULL DEFAULT TRUE
--
-- Irreversible in the sense that the derived values are gone, but they were
-- recomputed nightly from flow_reading_table and read by nothing, so there is
-- nothing to preserve. Reverting means re-running V29's ADD COLUMN.
-- ============================================================

-- ── Part A: Drop from existing tenant schemas ───────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing
        -- scheme_master_table is skipped for that table only, never aborting
        -- the whole migration.
        IF to_regclass(format('%I.scheme_master_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.scheme_master_table
                     DROP COLUMN IF EXISTS is_active',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Stop provisioning the column for new tenant schemas ─────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34/V35/V36/V37): preserve the current
-- implementation once under a versioned name, then wrap it. The captured base still
-- creates scheme_master_table with is_active (it inherits V30's table definition), so
-- the wrapper drops the column immediately afterwards rather than restating the whole
-- 570-line provisioning function just to remove one line from it.
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
        WHERE p.proname = 'create_tenant_schema_v39_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v39_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v39_base(schema_name);

    -- Retire is_active for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.scheme_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.scheme_master_table
                 DROP COLUMN IF EXISTS is_active',
            schema_name);
    END IF;
END;
$func$;
