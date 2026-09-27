-- ============================================================
-- Migration: V38 - External (state) public scheme code on
--                  scheme_master_table
-- ------------------------------------------------------------
-- The state JJM master data carries a public identifier per scheme
-- ("SCH-034035"), distinct from both ids we already hold:
--
--   centre_scheme_id  the IMIS id from the central system
--   state_scheme_id   the SMT id from the state system
--   state_scheme_code the state system's *public* code  <- added here
--
-- The first two are numeric ids from two different source systems and are
-- what the ingestion matches on today. The public code is the handle the
-- state's own exports, support tickets and reconciliation sheets quote, so
-- storing it lets any of those resolve one of our schemes without guessing
-- from a free-text name that several schemes can share.
--
--   scheme_master_table
--     + state_scheme_code  VARCHAR(255)  -- the state system's public code, NULL when unknown
--
-- Named for symmetry with department_location_master_table.state_dept_id
-- (V37) and user_table.state_user_id (V36): identifiers we adopt from an
-- upstream system rather than mint. "code" rather than "id" because
-- state_scheme_id is already taken by the numeric SMT id and the two must
-- not be confused.
--
-- Nullable: every pre-existing scheme, and every scheme created through the
-- app rather than the state sheet, simply has no such code. A partial
-- UNIQUE index enforces that one public code maps to at most one live
-- scheme per tenant while leaving those NULLs unconstrained.
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
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
                     ADD COLUMN IF NOT EXISTS state_scheme_code VARCHAR(255)',
                tenant_schema);
            EXECUTE format(
                'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_scheme_state_scheme_code
                     ON %1$I.scheme_master_table(state_scheme_code)
                     WHERE state_scheme_code IS NOT NULL AND deleted_at IS NULL',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34/V35/V36/V37): preserve the
-- current implementation once under a versioned name, then wrap it to add the
-- column. The captured base therefore already includes the V37 provisioning,
-- which must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v38_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v38_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v38_base(schema_name);

    -- External (state) public scheme code for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.scheme_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.scheme_master_table
                 ADD COLUMN IF NOT EXISTS state_scheme_code VARCHAR(255)',
            schema_name);
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_scheme_state_scheme_code
                 ON %1$I.scheme_master_table(state_scheme_code)
                 WHERE state_scheme_code IS NOT NULL AND deleted_at IS NULL',
            schema_name);
    END IF;
END;
$func$;
