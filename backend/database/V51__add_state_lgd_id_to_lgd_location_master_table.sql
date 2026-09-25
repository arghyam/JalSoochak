-- ============================================================
-- Migration: V49 - External (state) LGD location identifier on
--                  lgd_location_master_table
-- ------------------------------------------------------------
-- The state JJM master data carries a public identifier per LGD node
-- ("DST-060" for a district). Storing it lets a re-import, a support query
-- or a reconciliation against the state's sheet resolve one of our LGD
-- nodes without falling back to matching on a free-text title, which two
-- nodes can share.
--
--   lgd_location_master_table
--     + state_lgd_id  VARCHAR(255)   -- the state system's public id, NULL when unknown
--
-- Named for symmetry with department_location_master_table.state_dept_id
-- (V37), user_table.state_user_id (V36) and
-- scheme_master_table.state_scheme_code (V42): identifiers we adopt from an
-- upstream system rather than mint. Not to be confused with lgd_code, the
-- national Local Government Directory code, which the state's public id
-- neither replaces nor mirrors.
--
-- Nullable: every pre-existing node, and every node created through the
-- app rather than the state sheet, simply has no such id. A partial UNIQUE
-- index enforces that one state id maps to at most one live LGD node per
-- tenant while leaving those NULLs unconstrained. The state's ids carry a
-- per-level prefix, so one index spans every level.
--
-- Runs in one transaction. As in V47, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unwrapped create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned without the column unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Backfill existing tenant schemas ────────────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass so a partially-provisioned schema missing
        -- lgd_location_master_table is skipped for that table only, never
        -- aborting the whole migration.
        IF to_regclass(format('%I.lgd_location_master_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.lgd_location_master_table
                     ADD COLUMN IF NOT EXISTS state_lgd_id VARCHAR(255)',
                tenant_schema);
            EXECUTE format(
                'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_lgd_state_lgd_id
                     ON %1$I.lgd_location_master_table(state_lgd_id)
                     WHERE state_lgd_id IS NOT NULL AND deleted_at IS NULL',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same column ───────────────
-- Wrapper pattern (as used by V36/V37/V42/V47): preserve the current
-- implementation once under a versioned name, then wrap it to add the column.
-- The captured base therefore already includes the V47 provisioning, which
-- must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v49_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v49_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v49_base(schema_name);

    -- External (state) LGD location identifier for new tenant schemas.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.lgd_location_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.lgd_location_master_table
                 ADD COLUMN IF NOT EXISTS state_lgd_id VARCHAR(255)',
            schema_name);
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS uq_%1$s_lgd_state_lgd_id
                 ON %1$I.lgd_location_master_table(state_lgd_id)
                 WHERE state_lgd_id IS NOT NULL AND deleted_at IS NULL',
            schema_name);
    END IF;
END;
$func$;
