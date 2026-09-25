-- ============================================================
-- Migration: V47 - Move the per-contact preference tables into tenant schemas
-- ------------------------------------------------------------
-- USER-PREFERENCE-TENANT-SCHEMA
--
-- V33's common_schema.user_channel_preference and user_language_preference
-- hold per-tenant rows told apart by tenant_id. Both move into every tenant
-- schema, where the schema is the tenant boundary, so tenant_id goes and
-- contact_id alone is unique:
--
--   tenant_<code>.user_channel_preference   (contact_id UNIQUE, channel_value)
--   tenant_<code>.user_language_preference  (contact_id UNIQUE, language_value)
--
-- This is the expand half of the move; V48 drops the common_schema tables.
--
--   * Rows are copied with contact_id reduced to digits, the form telemetry
--     has always written but pre-V33 rows were not stored in. Where that folds
--     several rows of one tenant onto one contact, the latest updated_at wins.
--   * Rows whose tenant is missing, soft-deleted or has no schema are skipped,
--     and counted in a NOTICE. V48 drops them with the table.
--   * Until V48, triggers keep both copies in step while telemetry pods of
--     both versions serve during the rollout. One on each common_schema table
--     mirrors writes from pre-V47 pods into the tenant table; one on each
--     tenant table mirrors writes from newer pods back into common_schema,
--     which pre-V47 pods still read. A mirrored write is not mirrored back
--     (pg_trigger_depth), and a mirror that fails only logs a WARNING rather
--     than failing the write it mirrors.
--
-- The copy and both mirrors apply a row only when it is at least as new as
-- the row it replaces, so they and telemetry's own writes can land in any
-- order.
--
-- Runs in one transaction. The SHARE lock on tenant_master_table, taken
-- first, waits for every createTenant transaction already running the
-- unpatched create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant is
-- provisioned without the tables. Creating each trigger locks its table
-- against writes until commit, so every write either commits before the copy
-- reads it or fires the trigger. The tenant-side triggers are created after
-- the copy, so the copy is not mirrored back.
--
-- Deploy tenant-service (this migration) before the telemetry-service that
-- reads the tenant tables. Every related change is marked
-- "USER-PREFERENCE-TENANT-SCHEMA".
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: The tables, for existing and new tenant schemas alike ───────────
CREATE OR REPLACE FUNCTION common_schema.create_user_preference_tables(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %1$I.user_channel_preference (
             id            BIGSERIAL     PRIMARY KEY,
             contact_id    VARCHAR(50)   NOT NULL,
             channel_value VARCHAR(100)  NOT NULL,
             created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
             updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),

             CONSTRAINT uq_user_channel_pref_contact UNIQUE (contact_id)
         )',
        schema_name);

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %1$I.user_language_preference (
             id             BIGSERIAL    PRIMARY KEY,
             contact_id     TEXT         NOT NULL,
             language_value TEXT,
             created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
             updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),

             CONSTRAINT uq_user_language_pref_contact UNIQUE (contact_id)
         )',
        schema_name);
END;
$func$;

DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        PERFORM common_schema.create_user_preference_tables(tenant_schema);
    END LOOP;
END $$;

-- ── Part B: Ensure new tenant schemas include the same tables ───────────────
-- Wrapper pattern (as used by V40/V41/V43): preserve the current implementation once under a
-- versioned name, then wrap it to add the USER-PREFERENCE-TENANT-SCHEMA tables.
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
        WHERE p.proname = 'create_tenant_schema_v47_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v47_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v47_base(schema_name);

    -- USER-PREFERENCE-TENANT-SCHEMA: per-contact preference tables for new tenant schemas.
    PERFORM common_schema.create_user_preference_tables(schema_name);

    -- Until V48: mirror their writes back to common_schema (Part E).
    PERFORM common_schema.create_user_preference_common_mirror(schema_name);
END;
$func$;

-- ── Part C: Mirror writes still reaching common_schema, until V48 ───────────
-- TG_ARGV[0] names the table's value column. The tenant is resolved as telemetry resolves it
-- (tenant_ || lower(trim(state_code))), limited to live tenants like the copy in Part D.
-- WHEN (pg_trigger_depth() = 0) skips a write that is itself Part E's mirror, so none bounces back.
CREATE OR REPLACE FUNCTION common_schema.mirror_user_preference_to_tenant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $func$
DECLARE
    value_column  CONSTANT TEXT := TG_ARGV[0];
    tenant_schema TEXT;
BEGIN
    SELECT 'tenant_' || lower(trim(t.state_code))
    INTO tenant_schema
    FROM common_schema.tenant_master_table t
    WHERE t.id = NEW.tenant_id
      AND t.deleted_at IS NULL;

    IF tenant_schema IS NULL
       OR to_regclass(format('%I.%I', tenant_schema, TG_TABLE_NAME)) IS NULL THEN
        RETURN NULL;
    END IF;

    EXECUTE format(
        'INSERT INTO %1$I.%2$I AS target (contact_id, %3$I, created_at, updated_at)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (contact_id) DO UPDATE
             SET %3$I = EXCLUDED.%3$I, updated_at = EXCLUDED.updated_at
             WHERE target.updated_at <= EXCLUDED.updated_at',
        tenant_schema, TG_TABLE_NAME, value_column)
    USING regexp_replace(NEW.contact_id, '\D', '', 'g'),
          to_jsonb(NEW) ->> value_column,
          NEW.created_at,
          NEW.updated_at;

    RETURN NULL;
EXCEPTION WHEN OTHERS THEN
    -- No contact id here: phone numbers are PII and stay out of WARNING-level logs.
    RAISE WARNING 'V47: could not mirror a write on common_schema.% for tenant %: % (SQLSTATE %)',
        TG_TABLE_NAME, NEW.tenant_id, SQLERRM, SQLSTATE;
    RETURN NULL;
END;
$func$;

CREATE TRIGGER trg_user_channel_preference_mirror_to_tenant
    AFTER INSERT OR UPDATE ON common_schema.user_channel_preference
    FOR EACH ROW
    WHEN (pg_trigger_depth() = 0)
    EXECUTE FUNCTION common_schema.mirror_user_preference_to_tenant('channel_value');

CREATE TRIGGER trg_user_language_preference_mirror_to_tenant
    AFTER INSERT OR UPDATE ON common_schema.user_language_preference
    FOR EACH ROW
    WHEN (pg_trigger_depth() = 0)
    EXECUTE FUNCTION common_schema.mirror_user_preference_to_tenant('language_value');

-- ── Part D: Copy existing rows into their tenant's schema ───────────────────
DO $$
DECLARE
    preference  RECORD;
    tenant      RECORD;
    total_rows  BIGINT;
    copied_rows BIGINT;
    tenant_rows BIGINT;
BEGIN
    FOR preference IN
        SELECT *
        FROM (VALUES ('user_channel_preference',  'channel_value'),
                     ('user_language_preference', 'language_value')) AS p(table_name, value_column)
    LOOP
        EXECUTE format('SELECT count(*) FROM common_schema.%I', preference.table_name) INTO total_rows;
        copied_rows := 0;

        FOR tenant IN
            SELECT t.id, 'tenant_' || lower(trim(t.state_code)) AS schema_name
            FROM common_schema.tenant_master_table t
            WHERE t.deleted_at IS NULL
        LOOP
            CONTINUE WHEN to_regclass(format('%I.%I', tenant.schema_name, preference.table_name)) IS NULL;

            EXECUTE format($sql$
                INSERT INTO %1$I.%2$I AS target (contact_id, %3$I, created_at, updated_at)
                SELECT DISTINCT ON (contact_id) contact_id, value, created_at, updated_at
                FROM (
                    SELECT regexp_replace(source.contact_id, '\D', '', 'g') AS contact_id,
                           source.%3$I AS value,
                           source.created_at,
                           source.updated_at,
                           source.id
                    FROM common_schema.%2$I source
                    WHERE source.tenant_id = $1
                ) normalised
                ORDER BY contact_id, updated_at DESC, created_at DESC, id DESC
                ON CONFLICT (contact_id) DO UPDATE
                    SET %3$I = EXCLUDED.%3$I, updated_at = EXCLUDED.updated_at
                    WHERE target.updated_at <= EXCLUDED.updated_at
                $sql$, tenant.schema_name, preference.table_name, preference.value_column)
            USING tenant.id;

            EXECUTE format('SELECT count(*) FROM common_schema.%I WHERE tenant_id = $1', preference.table_name)
            INTO tenant_rows
            USING tenant.id;
            copied_rows := copied_rows + tenant_rows;
        END LOOP;

        RAISE NOTICE 'V47: copied % of % row(s) from common_schema.%, skipped % (tenant missing, soft-deleted or without a schema)',
            copied_rows, total_rows, preference.table_name, total_rows - copied_rows;
    END LOOP;
END $$;

-- ── Part E: Mirror writes to the tenant tables back to common_schema, until V48 ─
-- Pre-V47 telemetry pods still read common_schema during the rollout, so a preference set through a
-- newer pod reaches them too. The mirror of Part C in reverse: the tenant comes from the table's
-- schema, and WHEN (pg_trigger_depth() = 0) skips a write that is itself Part C's mirror. V48 drops
-- these triggers with their function and takes the call out of create_tenant_schema().
CREATE OR REPLACE FUNCTION common_schema.mirror_user_preference_to_common()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $func$
DECLARE
    value_column    CONSTANT TEXT := TG_ARGV[0];
    owner_tenant_id INTEGER;
BEGIN
    SELECT t.id
    INTO owner_tenant_id
    FROM common_schema.tenant_master_table t
    WHERE 'tenant_' || lower(trim(t.state_code)) = TG_TABLE_SCHEMA
      AND t.deleted_at IS NULL;

    IF owner_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;

    EXECUTE format(
        'INSERT INTO common_schema.%1$I AS target (tenant_id, contact_id, %2$I, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5)
         ON CONFLICT (tenant_id, contact_id) DO UPDATE
             SET %2$I = EXCLUDED.%2$I, updated_at = EXCLUDED.updated_at
             WHERE target.updated_at <= EXCLUDED.updated_at',
        TG_TABLE_NAME, value_column)
    USING owner_tenant_id,
          NEW.contact_id,
          to_jsonb(NEW) ->> value_column,
          NEW.created_at,
          NEW.updated_at;

    RETURN NULL;
EXCEPTION WHEN OTHERS THEN
    -- No contact id here: phone numbers are PII and stay out of WARNING-level logs.
    RAISE WARNING 'V47: could not mirror a write on %.% back to common_schema: % (SQLSTATE %)',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, SQLERRM, SQLSTATE;
    RETURN NULL;
END;
$func$;

CREATE OR REPLACE FUNCTION common_schema.create_user_preference_common_mirror(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    EXECUTE format(
        'CREATE OR REPLACE TRIGGER trg_user_channel_preference_mirror_to_common
             AFTER INSERT OR UPDATE ON %1$I.user_channel_preference
             FOR EACH ROW
             WHEN (pg_trigger_depth() = 0)
             EXECUTE FUNCTION common_schema.mirror_user_preference_to_common(''channel_value'')',
        schema_name);

    EXECUTE format(
        'CREATE OR REPLACE TRIGGER trg_user_language_preference_mirror_to_common
             AFTER INSERT OR UPDATE ON %1$I.user_language_preference
             FOR EACH ROW
             WHEN (pg_trigger_depth() = 0)
             EXECUTE FUNCTION common_schema.mirror_user_preference_to_common(''language_value'')',
        schema_name);
END;
$func$;

DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        PERFORM common_schema.create_user_preference_common_mirror(tenant_schema);
    END LOOP;
END $$;
