-- ============================================================
-- Migration: V48 - Drop the common_schema user preference tables
-- ------------------------------------------------------------
-- USER-PREFERENCE-TENANT-SCHEMA
--
-- The contract half of V47's move. telemetry-service now reads and writes
-- only tenant_<code>.user_channel_preference and user_language_preference,
-- so the rollout bridge V47 built goes, with the common_schema tables:
--
--   * the triggers that mirror tenant writes back to common_schema, their
--     function, and the helper that creates them for a new tenant schema;
--   * create_tenant_schema()'s call to that helper;
--   * both common_schema tables, the triggers that mirror their writes into
--     the tenant schemas, and that trigger function.
--
-- A mirror that failed only logged a WARNING, so a write a pre-V47 pod made
-- may exist only in common_schema. Before the drop, V47's copy runs once
-- more and carries any such row into its tenant's schema. Rows it cannot
-- place (tenant missing, soft-deleted or without a schema) are counted in a
-- NOTICE and dropped with the tables.
--
-- Deploy only once no telemetry-service pod older than V47 is serving: those
-- pods read and write the common_schema tables.
--
-- Runs in one transaction. As in V47, the SHARE lock on tenant_master_table,
-- taken first, waits for every createTenant transaction already running V47's
-- create_tenant_schema() -- which calls the helper dropped here and creates
-- triggers on the function dropped here -- and holds new ones off until this
-- commits. The tenant-side triggers are dropped before the common_schema
-- tables are locked, the order in which a tenant write and its mirror take
-- them.
-- ============================================================

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part A: Stop mirroring tenant writes back to common_schema ──────────────
-- CASCADE drops the function's triggers in every tenant schema.
DROP FUNCTION common_schema.mirror_user_preference_to_common() CASCADE;
DROP FUNCTION common_schema.create_user_preference_common_mirror(TEXT);

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v47_base(schema_name);

    -- USER-PREFERENCE-TENANT-SCHEMA: per-contact preference tables for new tenant schemas.
    PERFORM common_schema.create_user_preference_tables(schema_name);
END;
$func$;

-- ── Part B: Carry over writes a failed mirror missed ────────────────────────
-- V47's Part D copy, except that a row is applied only when strictly newer than the tenant's, so the
-- rows the mirrors kept in step are left alone and the NOTICE counts only the ones carried over.
-- The SHARE lock holds off writes to the common_schema tables until they are dropped. It comes after
-- Part A, which already stops tenant writes from mirroring into them.
LOCK TABLE common_schema.user_channel_preference, common_schema.user_language_preference IN SHARE MODE;

DO $$
DECLARE
    preference  RECORD;
    tenant      RECORD;
    total_rows  BIGINT;
    placed_rows BIGINT;
    tenant_rows BIGINT;
    carried     BIGINT;
    carried_now BIGINT;
BEGIN
    FOR preference IN
        SELECT *
        FROM (VALUES ('user_channel_preference',  'channel_value'),
                     ('user_language_preference', 'language_value')) AS p(table_name, value_column)
    LOOP
        EXECUTE format('SELECT count(*) FROM common_schema.%I', preference.table_name) INTO total_rows;
        placed_rows := 0;
        carried := 0;

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
                    WHERE target.updated_at < EXCLUDED.updated_at
                $sql$, tenant.schema_name, preference.table_name, preference.value_column)
            USING tenant.id;
            GET DIAGNOSTICS carried_now = ROW_COUNT;
            carried := carried + carried_now;

            EXECUTE format('SELECT count(*) FROM common_schema.%I WHERE tenant_id = $1', preference.table_name)
            INTO tenant_rows
            USING tenant.id;
            placed_rows := placed_rows + tenant_rows;
        END LOOP;

        RAISE NOTICE 'V48: carried % row(s) of common_schema.% over to the tenant schemas, dropping % (tenant missing, soft-deleted or without a schema)',
            carried, preference.table_name, total_rows - placed_rows;
    END LOOP;
END $$;

-- ── Part C: Drop the common_schema tables ───────────────────────────────────
-- Each table takes its mirror_to_tenant trigger with it.
DROP TABLE common_schema.user_channel_preference;
DROP TABLE common_schema.user_language_preference;
DROP FUNCTION common_schema.mirror_user_preference_to_tenant();
