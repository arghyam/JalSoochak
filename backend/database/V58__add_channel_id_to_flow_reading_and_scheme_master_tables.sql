-- ============================================================
-- Migration: V58 - channel_id on flow_reading_table and scheme_master_table
-- ------------------------------------------------------------
--   common_schema.channel_master_table
--     + rows 1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN  (channel_type 1)
--   flow_reading_table
--     + channel_id  INTEGER  -> channel_master_table(id), NOT VALID
--     + trigger keeping channel and channel_id in step
--   scheme_master_table
--     + channel_id  INTEGER  -> channel_master_table(id)
--
-- The first step of replacing each table's channel column with a key
-- into channel_master_table:
--
--   V58   adds channel_id and the trigger
--   V59   fills flow_reading_table.channel_id and validates its key
--   then  the services read and write only channel_id
--   then  once no older pod serves, a later migration drops the
--         trigger and both channel columns
--
-- The ids seeded are the codes ReadingChannel already publishes on
-- MeterReadingEvent and analytics already stores, so neither changes.
--
-- Until channel is dropped, pods that write either column serve side
-- by side, and the trigger copies each write to the other column.
-- scheme_master_table gets no trigger: its channel is NULL in every
-- row, and its one writer fails before writing while
-- TENANT_SUPPORTED_CHANNELS is unset, as it is in production.
--
-- A NULL channel stays NULL rather than becoming BFM: it is either a
-- BFM reading from before the channel was recorded or a row with no
-- reading (placeholder, location, meter change).
--
-- flow_reading_table's key is added NOT VALID, since validating it
-- here would scan the whole table under the ACCESS EXCLUSIVE lock the
-- ALTER takes. V59 validates it after the backfill, under a lock that
-- lets reads and writes through. scheme_master_table's channel_id is
-- new and so NULL in every row, and its key is validated as it is
-- added.
--
-- channel_id gets no index. The channel indexes it replaces are almost
-- never scanned, and channel_master_table rows are soft-deleted, never
-- removed, so the keys need none.
--
-- Adding a column, key and trigger touches the catalogue only, but
-- takes a brief ACCESS EXCLUSIVE lock on the table. As in V46,
-- lock_timeout stops one queued behind a long reader from blocking
-- every reader and writer behind it; a timed-out table is retried a
-- few times before the migration gives up.
--
-- Provisioning is patched first, and provisioning already running the
-- unpatched function is waited out, before the tenants to change are
-- listed, so no tenant can be created without the columns unseen.
--
-- The .sql.conf beside this file runs it outside Flyway's transaction,
-- so each table's changes commit, releasing their locks, before the
-- next table's are taken. A failure leaves the tables before it
-- changed. Every step skips what is already done, so after
-- `flyway repair` a re-run carries on from the failed table.
-- ============================================================

-- ── Part A: Seed the reading channels ───────────────────────────────────────
-- The ids are given rather than drawn from the sequence, since they are the codes ReadingChannel
-- already uses. A row an environment already has under one of these ids or titles is kept, and the
-- check that follows fails the migration unless each id carries its channel's title.
INSERT INTO common_schema.channel_master_table (id, title, channel_type)
VALUES (1, 'BFM', 1),
       (2, 'ELM', 1),
       (3, 'PDU', 1),
       (4, 'IOT', 1),
       (5, 'MAN', 1)
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    mismatches TEXT;
BEGIN
    SELECT string_agg(format('id %s is %s, not %s', expected.id, coalesce(m.title, 'missing'), expected.title),
                      ', ' ORDER BY expected.id)
    INTO mismatches
    FROM (VALUES (1, 'BFM'), (2, 'ELM'), (3, 'PDU'), (4, 'IOT'), (5, 'MAN')) AS expected(id, title)
    LEFT JOIN common_schema.channel_master_table m ON m.id = expected.id
    WHERE m.title IS DISTINCT FROM expected.title;

    IF mismatches IS NOT NULL THEN
        RAISE EXCEPTION 'V58: common_schema.channel_master_table does not hold the reading channels under the ids ReadingChannel uses: %',
            mismatches;
    END IF;
END $$;

-- So that ids drawn from the sequence start after the seeded ones.
SELECT setval(pg_get_serial_sequence('common_schema.channel_master_table', 'id'),
              (SELECT max(id) FROM common_schema.channel_master_table));

-- ── Part B: Keep flow_reading_table.channel and channel_id in step ──────────
-- A write that changes channel_id sets channel to that channel's title. Any other write sets
-- channel_id from channel, so a pod that writes only channel never leaves a stale channel_id behind.
-- An id with no channel leaves channel NULL and the key rejects the row; a code with no channel is
-- rejected here.
CREATE OR REPLACE FUNCTION common_schema.sync_flow_reading_channel()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $func$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.channel_id IS NOT NULL)
       OR (TG_OP = 'UPDATE' AND NEW.channel_id IS DISTINCT FROM OLD.channel_id) THEN
        SELECT c.title
        INTO NEW.channel
        FROM common_schema.channel_master_table c
        WHERE c.id = NEW.channel_id;
    ELSIF NEW.channel IS NULL THEN
        NEW.channel_id := NULL;
    ELSE
        SELECT c.id
        INTO NEW.channel_id
        FROM common_schema.channel_master_table c
        WHERE c.title = NEW.channel;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'channel "%" on %.flow_reading_table is not a title in common_schema.channel_master_table',
                NEW.channel, TG_TABLE_SCHEMA
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$func$;

-- ── Part C: Ensure new tenant schemas get the same ──────────────────────────
-- Wrapper pattern (as used by V56/V57): preserve the current implementation once under a versioned
-- name, then wrap it. Outside a transaction, renaming the function and creating the wrapper would
-- commit apart, and a tenant created between the two would find no create_tenant_schema(). So the
-- current function is copied under the versioned name instead, and the wrapper replaces it in one
-- statement. A new schema's tables are empty, so their keys are validated as they are added.
DO $$
BEGIN
    IF to_regprocedure('common_schema.create_tenant_schema_v58_base(text)') IS NULL THEN
        EXECUTE replace(
            pg_get_functiondef('common_schema.create_tenant_schema(text)'::regprocedure),
            'FUNCTION common_schema.create_tenant_schema(',
            'FUNCTION common_schema.create_tenant_schema_v58_base(');
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v58_base(schema_name);

    -- channel_id, its key and comment, and the trigger keeping it in step with channel.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS channel_id INTEGER
                     CONSTRAINT fk_flow_channel REFERENCES common_schema.channel_master_table(id)',
            schema_name);
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.channel_id IS %L', schema_name,
            'The channel the reading came through, a common_schema.channel_master_table id: '
            || '1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN, the codes ReadingChannel publishes. '
            || 'NULL on rows with no reading (placeholder, location and meter-change rows) '
            || 'and on BFM readings from before the channel was recorded.');
        EXECUTE format(
            'CREATE OR REPLACE TRIGGER trg_flow_reading_channel_sync
                 BEFORE INSERT OR UPDATE OF channel, channel_id ON %1$I.flow_reading_table
                 FOR EACH ROW
                 EXECUTE FUNCTION common_schema.sync_flow_reading_channel()',
            schema_name);
    END IF;

    IF to_regclass(format('%I.scheme_master_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.scheme_master_table
                 ADD COLUMN IF NOT EXISTS channel_id INTEGER
                     CONSTRAINT fk_scheme_channel REFERENCES common_schema.channel_master_table(id)',
            schema_name);
        EXECUTE format('COMMENT ON COLUMN %I.scheme_master_table.channel_id IS %L', schema_name,
            'The channel the scheme''s readings are sent through, as chosen in the WhatsApp channel '
            || 'selection, a common_schema.channel_master_table id: 1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN. '
            || 'NULL until one is chosen.');
    END IF;
END;
$func$;

-- ── Part D: Wait out provisioning already running ───────────────────────────
-- A create_tenant_schema() call that began before Part C committed runs the unwrapped function, and
-- Parts E and F cannot see its schema until it commits. Every call runs inside the transaction of
-- TenantManagementServiceImpl.createTenant, which writes tenant_master_table first, so a SHARE lock
-- on that table waits for each such call to commit. It holds up only writers to that table, not
-- readers, so it is taken before lock_timeout is set, and released as soon as it is granted.
DO $$
BEGIN
    LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;
END $$;

-- Session-level: SET LOCAL would not outlive the statement outside a transaction.
SET lock_timeout = '3s';

-- ── Part E: flow_reading_table in existing tenant schemas ───────────────────
-- The column, key and comment are added in one go, so a table with the column has them all. The
-- trigger goes in the same transaction, so no row is written in between without a channel_id.
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

        IF NOT EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = flow_table
              AND attname = 'channel_id'
              AND NOT attisdropped
        ) THEN
            changes := changes
                || format(
                    'ALTER TABLE %1$I.flow_reading_table
                         ADD COLUMN channel_id INTEGER,
                         ADD CONSTRAINT fk_flow_channel FOREIGN KEY (channel_id)
                             REFERENCES common_schema.channel_master_table(id) NOT VALID',
                    tenant_schema)
                || format('COMMENT ON COLUMN %I.flow_reading_table.channel_id IS %L', tenant_schema,
                    'The channel the reading came through, a common_schema.channel_master_table id: '
                    || '1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN, the codes ReadingChannel publishes. '
                    || 'NULL on rows with no reading (placeholder, location and meter-change rows) '
                    || 'and on BFM readings from before the channel was recorded.');
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_trigger
            WHERE tgrelid = flow_table
              AND tgname = 'trg_flow_reading_channel_sync'
        ) THEN
            changes := changes
                || format(
                    'CREATE TRIGGER trg_flow_reading_channel_sync
                         BEFORE INSERT OR UPDATE OF channel, channel_id ON %1$I.flow_reading_table
                         FOR EACH ROW
                         EXECUTE FUNCTION common_schema.sync_flow_reading_channel()',
                    tenant_schema);
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
                RAISE NOTICE 'V58: lock not available on %.flow_reading_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        changed := changed + 1;
    END LOOP;

    RAISE NOTICE 'V58: added channel_id to flow_reading_table in % tenant schema(s)', changed;
END $$;

-- ── Part F: scheme_master_table in existing tenant schemas ──────────────────
-- Committed apart from Part E's table, so the two ACCESS EXCLUSIVE locks are never held together.
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

        CONTINUE WHEN EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = scheme_table
              AND attname = 'channel_id'
              AND NOT attisdropped
        );

        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                EXECUTE format(
                    'ALTER TABLE %1$I.scheme_master_table
                         ADD COLUMN channel_id INTEGER
                             CONSTRAINT fk_scheme_channel REFERENCES common_schema.channel_master_table(id)',
                    tenant_schema);
                EXECUTE format('COMMENT ON COLUMN %I.scheme_master_table.channel_id IS %L', tenant_schema,
                    'The channel the scheme''s readings are sent through, as chosen in the WhatsApp channel '
                    || 'selection, a common_schema.channel_master_table id: 1 BFM, 2 ELM, 3 PDU, 4 IOT, 5 MAN. '
                    || 'NULL until one is chosen.');
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V58: lock not available on %.scheme_master_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        changed := changed + 1;
    END LOOP;

    RAISE NOTICE 'V58: added channel_id to scheme_master_table in % tenant schema(s)', changed;
END $$;

RESET lock_timeout;
