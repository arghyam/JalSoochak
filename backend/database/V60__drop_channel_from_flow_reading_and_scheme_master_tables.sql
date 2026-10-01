-- ============================================================
-- Migration: V60 - Drop channel from flow_reading_table and scheme_master_table
-- ------------------------------------------------------------
--   flow_reading_table
--     - trg_flow_reading_channel_sync
--     - channel  VARCHAR(50), with idx_<tenant>_flow_channel
--   scheme_master_table
--     - channel  INTEGER, with idx_<tenant>_scheme_channel
--   common_schema.sync_flow_reading_channel()  dropped
--
-- The last step of replacing each table's channel column with
-- channel_id (see V58): the services now read and write only
-- channel_id, so the trigger keeping the two in step and the old
-- columns go. Run it only once no pod that writes channel still serves.
--
-- The trigger fires on UPDATE OF channel, so it depends on the column
-- and goes in the same transaction, before the column. The
-- drops are RESTRICT, so anything else found depending on a column, or
-- a trigger still calling the function, fails the migration rather
-- than being dropped with it. Each column's index goes with it.
--
-- Dropping a column touches the catalogue only, but takes a brief
-- ACCESS EXCLUSIVE lock on the table. As in V58, lock_timeout stops
-- one queued behind a long reader from blocking every reader and
-- writer behind it; a timed-out table is retried a few times before
-- the migration gives up.
--
-- Provisioning is patched first, and provisioning already running the
-- unpatched function is waited out, before the tenants to drop from
-- are listed, so no tenant can be created with the columns unseen.
--
-- The .sql.conf beside this file runs it outside Flyway's transaction,
-- so each table's drop commits, releasing its lock, before the next
-- table's is taken. A failure leaves the tables before it changed.
-- Every step skips what is already done, so a re-run carries on from
-- the failed table.
-- ============================================================

-- ── Part A: Stop new tenant schemas getting the columns and trigger ─────────
-- V30's body, renamed create_tenant_schema_v31_base, creates both columns and their indexes, and
-- V58's wrapper, today's create_tenant_schema(), creates the trigger. As in V55, the chain is walked
-- from create_tenant_schema() and each body patched in place from its full definition. A wrapper
-- would leave every new tenant creating the columns only to drop them.
--
-- notification_table has a channel column of its own, which stays: each column line is matched only
-- inside its own table's CREATE TABLE string, and each index line by its table.
DO $$
DECLARE
    fn_name     TEXT := 'create_tenant_schema';
    fn_oid      OID;
    fn_src      TEXT;
    fn_def      TEXT;
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
            RAISE EXCEPTION 'V60: common_schema.%(text) is called by the create_tenant_schema() chain but does not exist',
                fn_name;
        END IF;

        fn_def := pg_get_functiondef(fn_oid);

        -- Each pattern removes one statement or column line with the line break before it. (?:[^']|'')
        -- keeps a match inside the format string it starts in, '' being a quote escaped within it.
        patched_def := fn_def;
        patched_def := regexp_replace(patched_def,
            '\n[ \t]*EXECUTE format\(\s*''CREATE OR REPLACE TRIGGER trg_flow_reading_channel_sync[^'']*'',\s*schema_name\);',
            '');
        patched_def := replace(patched_def,
            '-- channel_id, its key and comment, and the trigger keeping it in step with channel.',
            '-- channel_id, its key and comment.');
        patched_def := regexp_replace(patched_def,
            '(\.flow_reading_table \((?:[^'']|'''')*)\n[ \t]*channel[ \t]+VARCHAR\(50\),',
            '\1');
        patched_def := regexp_replace(patched_def,
            '(\.scheme_master_table \((?:[^'']|'''')*)\n[ \t]*channel[ \t]+INTEGER,',
            '\1');
        patched_def := regexp_replace(patched_def,
            '\n[ \t]*EXECUTE format\(''CREATE INDEX [^'']*\.(flow_reading_table|scheme_master_table)\(channel\)'',\s*schema_name\);',
            '', 'g');

        IF patched_def ~ 'sync_flow_reading_channel'
           OR patched_def ~ '\.(flow_reading_table|scheme_master_table)\(channel\)'
           OR patched_def ~ '\.(flow_reading_table|scheme_master_table) \((?:[^'']|'''')*\n[ \t]*channel[ \t]' THEN
            RAISE EXCEPTION 'V60 patch failed: common_schema.%() creates a channel column, its index or its trigger in a form this migration does not remove',
                fn_name;
        END IF;

        IF patched_def <> fn_def THEN
            EXECUTE patched_def;
            patched := patched + 1;
        END IF;

        -- Each wrapper runs the function it wraps first; the chain ends at a body with no such call.
        fn_name := substring(fn_src FROM 'PERFORM\s+common_schema\.(create_tenant_schema_[a-z0-9_]+)\s*\(');
        EXIT WHEN fn_name IS NULL;
    END LOOP;

    -- Patching nothing is not an error: a re-run after a failure in a later part finds this already done.
    RAISE NOTICE 'V60: removed the channel columns and trigger from % tenant provisioning function(s)', patched;
END $$;

-- ── Part B: Wait out provisioning already running ───────────────────────────
-- A create_tenant_schema() call that began before Part A committed runs the unpatched function, and
-- Parts C and D cannot see its schema until it commits. Every call runs inside the transaction of
-- TenantManagementServiceImpl.createTenant, which writes tenant_master_table first, so a SHARE lock
-- on that table waits for each such call to commit. It holds up only writers to that table, not
-- readers, so it is taken before lock_timeout is set, and released as soon as it is granted.
DO $$
BEGIN
    LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;
END $$;

-- Session-level: SET LOCAL would not outlive the statement outside a transaction.
SET lock_timeout = '3s';

-- ── Part C: flow_reading_table in existing tenant schemas ───────────────────
-- The trigger and the column go in one transaction, so the table is never left with one and not the
-- other, and its ACCESS EXCLUSIVE lock is taken once.
DO $$
DECLARE
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    flow_table    REGCLASS;
    changes       TEXT[];
    change_sql    TEXT;
    changed       INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        flow_table := to_regclass(format('%I.flow_reading_table', tenant_schema));
        CONTINUE WHEN flow_table IS NULL;

        changes := ARRAY[]::TEXT[];

        IF EXISTS (
            SELECT 1
            FROM pg_trigger
            WHERE tgrelid = flow_table
              AND tgname = 'trg_flow_reading_channel_sync'
        ) THEN
            changes := changes
                || format('DROP TRIGGER trg_flow_reading_channel_sync ON %I.flow_reading_table', tenant_schema);
        END IF;

        IF EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = flow_table
              AND attname = 'channel'
              AND NOT attisdropped
        ) THEN
            changes := changes
                || format('ALTER TABLE %I.flow_reading_table DROP COLUMN channel', tenant_schema);
        END IF;

        CONTINUE WHEN cardinality(changes) = 0;

        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                FOREACH change_sql IN ARRAY changes LOOP
                    EXECUTE change_sql;
                END LOOP;
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V60: lock not available on %.flow_reading_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        changed := changed + 1;
    END LOOP;

    RAISE NOTICE 'V60: dropped channel from flow_reading_table in % tenant schema(s)', changed;
END $$;

-- ── Part D: scheme_master_table in existing tenant schemas ──────────────────
-- Committed apart from Part C's table, so the two ACCESS EXCLUSIVE locks are never held together.
DO $$
DECLARE
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    scheme_table  REGCLASS;
    changed       INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        scheme_table := to_regclass(format('%I.scheme_master_table', tenant_schema));
        CONTINUE WHEN scheme_table IS NULL;

        CONTINUE WHEN NOT EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = scheme_table
              AND attname = 'channel'
              AND NOT attisdropped
        );

        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                EXECUTE format('ALTER TABLE %I.scheme_master_table DROP COLUMN channel', tenant_schema);
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V60: lock not available on %.scheme_master_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        changed := changed + 1;
    END LOOP;

    RAISE NOTICE 'V60: dropped channel from scheme_master_table in % tenant schema(s)', changed;
END $$;

RESET lock_timeout;

-- ── Part E: Drop the trigger function ───────────────────────────────────────
-- Last, once Part C has dropped every trigger calling it.
DROP FUNCTION IF EXISTS common_schema.sync_flow_reading_channel();
