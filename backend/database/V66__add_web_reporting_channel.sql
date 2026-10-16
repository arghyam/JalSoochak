-- ============================================================
-- Migration: V66 - WEB reporting channel
-- ------------------------------------------------------------
--   common_schema.channel_master_table
--     + row 10 WEB  (channel_type 2)
--   flow_reading_table
--     reported_via_id column comment now lists WEB
--
-- WEB is the staff web console: a Section Officer or Sub-Divisional
-- Officer correcting a reading on the Fix-readings screen, signed in
-- with their own token. Until now such a correction was recorded as
-- API, the channel of state IT systems holding a tenant API key.
-- 8 and 9 are EMAIL and SMS (V63), so WEB takes 10.
--
-- Rows already written keep their reported_via_id; the column records
-- the latest writer, and only a later write changes it.
--
-- Runs in one transaction. As in V65, the SHARE lock on
-- tenant_master_table, taken before the comments change, waits for every
-- createTenant transaction already running the unwrapped
-- create_tenant_schema() -- each writes tenant_master_table before
-- provisioning -- and holds new ones off until this commits, so no tenant
-- is provisioned with the old comment unseen. COMMENT takes only a
-- SHARE UPDATE EXCLUSIVE lock on flow_reading_table, which reads and
-- writes do not wait for.
-- ============================================================

-- ── Part A: Seed the reporting channel ──────────────────────────────────────
-- As in V58, V61 and V63, the id is given rather than drawn from the sequence, since it is the code
-- ReportingChannel uses. A row an environment already has under this id or title is kept, and the
-- check that follows fails the migration unless the id carries the channel's title and type.
INSERT INTO common_schema.channel_master_table (id, title, channel_type)
VALUES (10, 'WEB', 2)
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    mismatches TEXT;
BEGIN
    SELECT string_agg(format('id %s is %s (channel_type %s), not %s (channel_type 2)',
                             expected.id, coalesce(m.title, 'missing'), coalesce(m.channel_type::TEXT, 'NULL'),
                             expected.title),
                      ', ' ORDER BY expected.id)
    INTO mismatches
    FROM (VALUES (6, 'WHATSAPP'), (7, 'API'), (10, 'WEB')) AS expected(id, title)
    LEFT JOIN common_schema.channel_master_table m ON m.id = expected.id
    WHERE m.title IS DISTINCT FROM expected.title
       OR m.channel_type IS DISTINCT FROM 2;

    IF mismatches IS NOT NULL THEN
        RAISE EXCEPTION 'V66: common_schema.channel_master_table does not hold the reporting channels under the ids ReportingChannel uses: %',
            mismatches;
    END IF;
END $$;

-- So that ids drawn from the sequence start after the seeded ones.
SELECT setval(pg_get_serial_sequence('common_schema.channel_master_table', 'id'),
              (SELECT max(id) FROM common_schema.channel_master_table));

LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;

-- ── Part B: The comment in existing tenant schemas ──────────────────────────
DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        -- Guard with to_regclass, and on the column, so a partially-provisioned schema is skipped
        -- instead of aborting the whole migration.
        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL
           AND EXISTS (SELECT 1
                       FROM information_schema.columns
                       WHERE table_schema = tenant_schema
                         AND table_name = 'flow_reading_table'
                         AND column_name = 'reported_via_id') THEN
            EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.reported_via_id IS %L', tenant_schema,
                'How the row was reported, a common_schema.channel_master_table id of channel_type 2: '
                || '6 WHATSAPP, 7 API, 10 WEB, the codes ReportingChannel uses. Set on every row written and '
                || 'replaced by each later write of the reading. NULL on rows written before V61.');
        END IF;
    END LOOP;
END $$;

-- ── Part C: Ensure new tenant schemas get the same comment ──────────────────
-- Wrapper pattern (as used by V53/V56/V57/V65): preserve the current implementation once under a
-- versioned name, then wrap it to replace the comment. The captured base therefore already includes
-- the V61 comment this replaces, and must run before this migration.
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
        WHERE p.proname = 'create_tenant_schema_v65_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v65_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v65_base(schema_name);

    -- The reported_via_id comment, listing WEB.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.reported_via_id IS %L', schema_name,
            'How the row was reported, a common_schema.channel_master_table id of channel_type 2: '
            || '6 WHATSAPP, 7 API, 10 WEB, the codes ReportingChannel uses. Set on every row written and '
            || 'replaced by each later write of the reading. NULL on rows written before V61.');
    END IF;
END;
$func$;
