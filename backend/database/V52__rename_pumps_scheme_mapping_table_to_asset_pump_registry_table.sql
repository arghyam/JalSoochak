-- ============================================================
-- Migration: V50 - Rename pumps_scheme_mapping_table to
--                  asset_pump_registry_table
-- ------------------------------------------------------------
-- The table is a registry of the pump assets installed on a scheme, not
-- a mapping between two entities. This renames it in every tenant schema,
-- with the objects whose names were derived from it:
--
--   pumps_scheme_mapping_table           -> asset_pump_registry_table
--   pumps_scheme_mapping_table_id_seq    -> asset_pump_registry_table_id_seq
--   pumps_scheme_mapping_table_pkey      -> asset_pump_registry_table_pkey
--   pumps_scheme_mapping_table_uuid_key  -> asset_pump_registry_table_uuid_key
--   idx_<schema>_psm_scheme              -> idx_<schema>_asset_pump_scheme
--
-- The fk_pumps_* constraints keep their names: they name the pump-to-scheme
-- and audit relations, which the rename leaves unchanged.
--
-- RENAME touches the catalogue only, so no table is rewritten. No service
-- reads or writes this table, so the ACCESS EXCLUSIVE lock each rename takes
-- is granted at once and, unlike V46, no per-tenant commit or retry is needed.
--
-- Runs in one transaction. As in V47, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running the
-- unpatched create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned under the old name unseen.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Ensure new tenant schemas get the new names ─────────────────────
-- V30 rewrote create_tenant_schema() in full, creating this table in its body, and V31 renamed
-- that function to create_tenant_schema_v31_base and wrapped it. Every later migration wrapped
-- it again, so the table name lives in one function body at the end of the wrapper chain. The
-- chain is walked from create_tenant_schema() and that body patched in place from its full
-- definition, as V46 did for the OCR column. A wrapper would leave every new tenant creating the
-- old table only to rename it.
--
-- V3, V4 and V10 also created this table, but their functions stopped being called when V30
-- replaced the body they sit under, so they are left as they are.
DO $$
DECLARE
    fn_name     TEXT := 'create_tenant_schema';
    fn_oid      OID;
    fn_src      TEXT;
    patched_def TEXT;
    patched     INT := 0;
BEGIN
    LOOP
        SELECT p.oid, p.prosrc
        INTO fn_oid, fn_src
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'common_schema'
          AND p.proname = fn_name
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text';

        IF NOT FOUND THEN
            RAISE EXCEPTION 'V50: common_schema.%(text) is called by the create_tenant_schema() chain but does not exist',
                fn_name;
        END IF;

        IF strpos(fn_src, 'pumps_scheme_mapping_table') > 0 THEN
            patched_def := replace(replace(
                pg_get_functiondef(fn_oid),
                'pumps_scheme_mapping_table', 'asset_pump_registry_table'),
                '_psm_scheme', '_asset_pump_scheme');

            IF strpos(patched_def, 'pumps_scheme_mapping') > 0 OR strpos(patched_def, '_psm_') > 0 THEN
                RAISE EXCEPTION 'V50 patch failed: common_schema.%() names the pump table in a form this migration does not rewrite',
                    fn_name;
            END IF;

            EXECUTE patched_def;
            patched := patched + 1;
        END IF;

        -- Each wrapper runs the function it wraps first; the chain ends at a body with no such call.
        fn_name := substring(fn_src FROM 'PERFORM\s+common_schema\.(create_tenant_schema_[a-z0-9_]+)\s*\(');
        EXIT WHEN fn_name IS NULL;
    END LOOP;

    IF patched = 0 THEN
        RAISE EXCEPTION 'V50: no function in the create_tenant_schema() chain creates pumps_scheme_mapping_table';
    END IF;

    RAISE NOTICE 'V50: renamed the pump table in % tenant provisioning function(s)', patched;
END $$;

-- ── Part B: Rename in existing tenant schemas ───────────────────────────────
-- Each rename is guarded, so a partially-provisioned schema missing the table, or any object
-- derived from it, is skipped for that object only.
DO $$
DECLARE
    tenant_schema TEXT;
    old_index     TEXT;
    renamed       INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        IF to_regclass(format('%I.pumps_scheme_mapping_table', tenant_schema)) IS NULL THEN
            CONTINUE;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I.pumps_scheme_mapping_table RENAME TO asset_pump_registry_table',
            tenant_schema);

        -- RENAME CONSTRAINT renames the index backing it as well.
        IF EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conrelid = format('%I.asset_pump_registry_table', tenant_schema)::regclass
              AND conname = 'pumps_scheme_mapping_table_pkey'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.asset_pump_registry_table
                     RENAME CONSTRAINT pumps_scheme_mapping_table_pkey TO asset_pump_registry_table_pkey',
                tenant_schema);
        END IF;

        IF EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conrelid = format('%I.asset_pump_registry_table', tenant_schema)::regclass
              AND conname = 'pumps_scheme_mapping_table_uuid_key'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.asset_pump_registry_table
                     RENAME CONSTRAINT pumps_scheme_mapping_table_uuid_key TO asset_pump_registry_table_uuid_key',
                tenant_schema);
        END IF;

        IF to_regclass(format('%I.pumps_scheme_mapping_table_id_seq', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER SEQUENCE %I.pumps_scheme_mapping_table_id_seq RENAME TO asset_pump_registry_table_id_seq',
                tenant_schema);
        END IF;

        old_index := format('idx_%s_psm_scheme', tenant_schema);
        IF to_regclass(format('%I.%I', tenant_schema, old_index)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER INDEX %I.%I RENAME TO %I',
                tenant_schema, old_index, format('idx_%s_asset_pump_scheme', tenant_schema));
        END IF;

        renamed := renamed + 1;
    END LOOP;

    RAISE NOTICE 'V50: renamed the pump table in % tenant schema(s)', renamed;
END $$;
