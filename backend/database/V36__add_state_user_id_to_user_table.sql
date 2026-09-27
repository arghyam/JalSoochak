-- ============================================================
-- Migration: V36 - External (state) user identifier on user_table
-- ------------------------------------------------------------
-- The state JJM user master carries a public identifier per person
-- ("USR-015755"). Storing it lets a re-import, a support query or a
-- reconciliation against the state's sheet resolve one of our users
-- without going through the encrypted phone number.
--
--   user_table
--     + state_user_id  VARCHAR(255)   -- the state system's public id, NULL when unknown
--
-- Named after scheme_master_table.state_scheme_id / centre_scheme_id,
-- which already follow the "<source system>_<entity>_id" convention for
-- identifiers we adopt rather than mint.
--
-- Nullable: every pre-existing user, and everyone onboarded through the
-- app rather than the state sheet, simply has no such id. A partial
-- UNIQUE index enforces that one state id maps to at most one live user
-- per tenant while leaving those NULLs unconstrained.
-- ============================================================

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing user_table is
        -- skipped for that table only, never aborting the whole migration.
        IF to_regclass(format('%I.user_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.user_table
                     ADD COLUMN IF NOT EXISTS state_user_id VARCHAR(255)',
                tenant_schema);
            EXECUTE format(
                'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_user_state_user_id
                     ON %1$I.user_table(state_user_id)
                     WHERE state_user_id IS NOT NULL AND deleted_at IS NULL',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V7/V10/V12/V31/V34/V35): preserve the current implementation
-- once under a versioned name, then wrap it to add the column. The captured base therefore
-- already includes the V35 provisioning, which must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v36_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v36_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v36_base(schema_name);

    -- External (state) user identifier for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.user_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.user_table
                 ADD COLUMN IF NOT EXISTS state_user_id VARCHAR(255)',
            schema_name);
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_user_state_user_id
                 ON %1$I.user_table(state_user_id)
                 WHERE state_user_id IS NOT NULL AND deleted_at IS NULL',
            schema_name);
    END IF;
END;
$func$;
