-- ============================================================
-- Migration: V61 - Replace flow_reading_table.reported_via with reported_via_id
-- ------------------------------------------------------------
--   common_schema.channel_master_table
--     + rows 6 WHATSAPP, 7 API  (channel_type 2)
--   flow_reading_table
--     - reported_via     VARCHAR(50)
--     + reported_via_id  INTEGER  -> channel_master_table(id)
--
-- reported_via_id records how a row was reported: the data-collection
-- channel (channel_type 2) the submission reached the service through,
-- as channel_id records the reading channel (channel_type 1). The ids
-- seeded are the codes ReportingChannel uses.
--
-- reported_via was provisioned in every tenant schema but never
-- written. It is dropped rather than retyped, since ALTER COLUMN TYPE
-- would rewrite the whole table under an ACCESS EXCLUSIVE lock; a
-- table with a value in it fails the migration rather than losing it.
-- Rows written before this migration keep a NULL reported_via_id.
--
-- The key is added NOT VALID, since validating it with the ALTER would
-- scan the whole table under that ALTER's ACCESS EXCLUSIVE lock. Each
-- table's key is validated once its ALTER has committed, under a lock
-- that lets reads and writes through.
--
-- reported_via_id gets no index, as channel_id gets none (see V58).
--
-- Dropping and adding a column touch the catalogue only, but take a
-- brief ACCESS EXCLUSIVE lock on the table. As in V58, lock_timeout
-- stops one queued behind a long reader from blocking every reader and
-- writer behind it; a timed-out table is retried a few times before
-- the migration gives up.
--
-- Provisioning is patched first, and provisioning already running the
-- unpatched function is waited out, before the tenants to change are
-- listed, so no tenant can be created with the old column unseen.
--
-- The .sql.conf beside this file runs it outside Flyway's transaction,
-- so each table's changes commit, releasing their locks, before the
-- next table's are taken. A failure leaves the tables before it
-- changed. Every step skips what is already done, so after
-- `flyway repair` a re-run carries on from the failed table.
-- ============================================================

-- ── Part A: Seed the reporting channels ─────────────────────────────────────
-- As in V58, the ids are given rather than drawn from the sequence, since they are the codes
-- ReportingChannel uses. A row an environment already has under one of these ids or titles is kept,
-- and the check that follows fails the migration unless each id carries its channel's title and type.
INSERT INTO common_schema.channel_master_table (id, title, channel_type)
VALUES (6, 'WHATSAPP', 2),
       (7, 'API', 2)
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
    FROM (VALUES (6, 'WHATSAPP'), (7, 'API')) AS expected(id, title)
    LEFT JOIN common_schema.channel_master_table m ON m.id = expected.id
    WHERE m.title IS DISTINCT FROM expected.title
       OR m.channel_type IS DISTINCT FROM 2;

    IF mismatches IS NOT NULL THEN
        RAISE EXCEPTION 'V61: common_schema.channel_master_table does not hold the reporting channels under the ids ReportingChannel uses: %',
            mismatches;
    END IF;
END $$;

-- So that ids drawn from the sequence start after the seeded ones.
SELECT setval(pg_get_serial_sequence('common_schema.channel_master_table', 'id'),
              (SELECT max(id) FROM common_schema.channel_master_table));

-- ── Part B: Stop new tenant schemas getting reported_via ────────────────────
-- V30's body, renamed create_tenant_schema_v31_base, creates the column. As in V60, the chain is
-- walked from create_tenant_schema() and each body patched in place from its full definition, the
-- column line matched only inside flow_reading_table's CREATE TABLE string.
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
            RAISE EXCEPTION 'V61: common_schema.%(text) is called by the create_tenant_schema() chain but does not exist',
                fn_name;
        END IF;

        fn_def := pg_get_functiondef(fn_oid);

        -- (?:[^']|'') keeps the match inside the format string it starts in, '' being a quote escaped
        -- within it.
        patched_def := regexp_replace(fn_def,
            '(\.flow_reading_table \((?:[^'']|'''')*)\n[ \t]*reported_via[ \t]+VARCHAR\(50\),',
            '\1');

        IF patched_def ~ '\.flow_reading_table \((?:[^'']|'''')*\n[ \t]*reported_via[ \t]' THEN
            RAISE EXCEPTION 'V61 patch failed: common_schema.%() creates reported_via in a form this migration does not remove',
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
    RAISE NOTICE 'V61: removed reported_via from % tenant provisioning function(s)', patched;
END $$;

-- ── Part C: Ensure new tenant schemas get reported_via_id ───────────────────
-- Wrapper pattern, as in V58: the current function is copied under a versioned name, and the wrapper
-- replaces it in one statement, so no tenant is created between the two with no create_tenant_schema().
-- A new schema's table is empty, so its key is validated as it is added.
DO $$
BEGIN
    IF to_regprocedure('common_schema.create_tenant_schema_v61_base(text)') IS NULL THEN
        EXECUTE replace(
            pg_get_functiondef('common_schema.create_tenant_schema(text)'::regprocedure),
            'FUNCTION common_schema.create_tenant_schema(',
            'FUNCTION common_schema.create_tenant_schema_v61_base(');
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v61_base(schema_name);

    -- reported_via_id, its key and comment.
    -- Guard with to_regclass so a partially-provisioned schema is skipped instead of aborting.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS reported_via_id INTEGER
                     CONSTRAINT fk_flow_reported_via REFERENCES common_schema.channel_master_table(id)',
            schema_name);
        EXECUTE format('COMMENT ON COLUMN %I.flow_reading_table.reported_via_id IS %L', schema_name,
            'How the row was reported, a common_schema.channel_master_table id of channel_type 2: '
            || '6 WHATSAPP, 7 API, the codes ReportingChannel uses. Set on every row written and '
            || 'replaced by each later write of the reading. NULL on rows written before V61.');
    END IF;
END;
$func$;

-- ── Part D: Wait out provisioning already running ───────────────────────────
-- A create_tenant_schema() call that began before Part C committed runs the unwrapped function, and
-- Part E cannot see its schema until it commits. Every call runs inside the transaction of
-- TenantManagementServiceImpl.createTenant, which writes tenant_master_table first, so a SHARE lock
-- on that table waits for each such call to commit. It holds up only writers to that table, not
-- readers, so it is taken before lock_timeout is set, and released as soon as it is granted.
DO $$
BEGIN
    LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;
END $$;

-- Session-level: SET LOCAL would not outlive the statement outside a transaction. A statement_timeout
-- set on the role or the database would cancel Part E part-way, since the timer runs for the whole DO
-- block, not for each table it commits.
SET statement_timeout = 0;
SET lock_timeout = '3s';

-- ── Part E: flow_reading_table in existing tenant schemas ───────────────────
-- The drop, the column, its key and comment go in one transaction, so a table is never left with
-- neither column, and its ACCESS EXCLUSIVE lock is taken once. The key is then validated in a
-- transaction of its own, under a SHARE UPDATE EXCLUSIVE lock.
DO $$
DECLARE
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    flow_table    REGCLASS;
    has_value     BOOLEAN;
    changes       TEXT[];
    change_sql    TEXT;
    key_validated BOOLEAN;
    changed       INT := 0;
    validated     INT := 0;
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
            FROM pg_attribute
            WHERE attrelid = flow_table
              AND attname = 'reported_via'
              AND NOT attisdropped
        ) THEN
            -- Checked before the ALTER, under no lock that holds up writes.
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.flow_reading_table WHERE reported_via IS NOT NULL)',
                tenant_schema)
            INTO has_value;

            IF has_value THEN
                RAISE EXCEPTION 'V61: %.flow_reading_table has rows with a reported_via, which this migration would drop',
                    tenant_schema;
            END IF;

            changes := changes
                || format('ALTER TABLE %I.flow_reading_table DROP COLUMN reported_via', tenant_schema);
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_attribute
            WHERE attrelid = flow_table
              AND attname = 'reported_via_id'
              AND NOT attisdropped
        ) THEN
            changes := changes
                || format(
                    'ALTER TABLE %1$I.flow_reading_table
                         ADD COLUMN reported_via_id INTEGER,
                         ADD CONSTRAINT fk_flow_reported_via FOREIGN KEY (reported_via_id)
                             REFERENCES common_schema.channel_master_table(id) NOT VALID',
                    tenant_schema)
                || format('COMMENT ON COLUMN %I.flow_reading_table.reported_via_id IS %L', tenant_schema,
                    'How the row was reported, a common_schema.channel_master_table id of channel_type 2: '
                    || '6 WHATSAPP, 7 API, the codes ReportingChannel uses. Set on every row written and '
                    || 'replaced by each later write of the reading. NULL on rows written before V61.');
        END IF;

        IF cardinality(changes) > 0 THEN
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
                    RAISE NOTICE 'V61: lock not available on %.flow_reading_table (attempt % of %), retrying',
                        tenant_schema, attempt, max_attempts;
                    PERFORM pg_sleep(attempt);
                END;
            END LOOP;

            COMMIT;
            changed := changed + 1;
        END IF;

        -- ── Validate fk_flow_reported_via ───────────────────────────────────────────────────────
        SELECT convalidated
        INTO key_validated
        FROM pg_constraint
        WHERE conrelid = flow_table
          AND conname = 'fk_flow_reported_via';

        IF NOT FOUND THEN
            RAISE EXCEPTION 'V61: %.flow_reading_table has reported_via_id but no fk_flow_reported_via', tenant_schema;
        END IF;

        CONTINUE WHEN key_validated;

        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                EXECUTE format('ALTER TABLE %I.flow_reading_table VALIDATE CONSTRAINT fk_flow_reported_via',
                    tenant_schema);
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V61: lock not available to validate %.flow_reading_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        validated := validated + 1;
    END LOOP;

    RAISE NOTICE 'V61: replaced reported_via with reported_via_id in % and validated fk_flow_reported_via in % tenant schema(s)',
        changed, validated;
END $$;

RESET lock_timeout;
RESET statement_timeout;
