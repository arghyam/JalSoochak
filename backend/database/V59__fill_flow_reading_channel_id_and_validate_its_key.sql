-- ============================================================
-- Migration: V59 - Fill flow_reading_table.channel_id and validate its key
-- ------------------------------------------------------------
--   flow_reading_table
--     channel_id       <- the channel_master_table id titled channel
--     fk_flow_channel  NOT VALID -> validated
--
-- The second step of replacing flow_reading_table.channel with
-- channel_id (see V58). Since V58 its trigger fills channel_id on every
-- write. This fills it on the rows written before, then validates the
-- key V58 added NOT VALID.
--
-- A NULL channel keeps a NULL channel_id. A channel that is not a
-- title in channel_master_table fails the migration, as it fails a
-- write under the trigger.
--
-- Rows are filled in batches by id, each committed before the next,
-- so no row stays locked for longer than a batch takes, and the dead
-- rows a batch leaves behind can be vacuumed while later batches run.
-- A short pause after each batch gives autovacuum, WAL archiving and
-- replicas room to keep up. Reads and inserts are never blocked; an
-- update or delete of a row in the batch being written waits until
-- that batch commits. The update fires V58's trigger, which sets
-- channel to the title it already holds.
--
-- VALIDATE CONSTRAINT then scans the table under a SHARE UPDATE
-- EXCLUSIVE lock, which lets reads and writes through and holds up
-- only DDL and vacuum on that table. As in V58, lock_timeout stops it
-- queueing behind a long-running lock holder, and a timed-out attempt
-- is retried a few times before the migration gives up.
--
-- On a large table this runs for a long time, as a single statement
-- however many batches it commits. Progress shows in
--   SELECT count(*) FROM <tenant>.flow_reading_table
--   WHERE channel IS NOT NULL AND channel_id IS NULL;
--
-- A tenant whose key is validated is done and skipped: this migration
-- filled and validated it, or it was provisioned after V58 with the
-- trigger in place from its first row. The .sql.conf beside this file
-- runs it outside Flyway's transaction, so after a failure and
-- `flyway repair` a re-run skips the finished tenants and, in the
-- failed one, writes only the rows still unfilled.
-- ============================================================

-- Session-level: SET LOCAL would not outlive the statement outside a transaction. A statement_timeout
-- set on the role or the database would cancel the fill part-way, since the timer runs for the whole
-- DO block, not for each batch it commits.
SET statement_timeout = 0;
SET lock_timeout = '3s';

DO $$
DECLARE
    batch_size    CONSTANT INT := 10000;
    batch_pause   CONSTANT DOUBLE PRECISION := 0.1;  -- seconds
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    flow_table    REGCLASS;
    key_validated BOOLEAN;
    first_id      BIGINT;
    last_id       BIGINT;
    batch_start   BIGINT;
    batch_rows    BIGINT;
    filled        BIGINT;
    finished      INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        flow_table := to_regclass(format('%I.flow_reading_table', tenant_schema));
        CONTINUE WHEN flow_table IS NULL;

        SELECT convalidated
        INTO key_validated
        FROM pg_constraint
        WHERE conrelid = flow_table
          AND conname = 'fk_flow_channel';

        IF NOT FOUND THEN
            RAISE EXCEPTION 'V59: %.flow_reading_table has no fk_flow_channel, which V58 adds', tenant_schema;
        END IF;

        CONTINUE WHEN key_validated;

        -- ── Fill channel_id ─────────────────────────────────────────────────────────────────────
        -- Every row written since V58 got its channel_id from the trigger, so the rows to fill all
        -- have ids no higher than today's highest.
        EXECUTE format('SELECT min(id), max(id) FROM %I.flow_reading_table', tenant_schema)
        INTO first_id, last_id;

        filled := 0;
        batch_start := first_id;

        WHILE batch_start <= last_id LOOP
            FOR attempt IN 1..max_attempts LOOP
                BEGIN
                    -- A channel with no title to match gets no id, and the trigger rejects the row.
                    EXECUTE format(
                        'UPDATE %I.flow_reading_table f
                         SET channel_id = (SELECT c.id
                                           FROM common_schema.channel_master_table c
                                           WHERE c.title = f.channel)
                         WHERE f.id BETWEEN $1 AND $2
                           AND f.channel IS NOT NULL
                           AND f.channel_id IS NULL',
                        tenant_schema)
                    USING batch_start, batch_start + batch_size - 1;

                    GET DIAGNOSTICS batch_rows = ROW_COUNT;
                    EXIT;
                EXCEPTION WHEN lock_not_available OR deadlock_detected THEN
                    IF attempt = max_attempts THEN
                        RAISE;
                    END IF;
                    RAISE NOTICE 'V59: % on %.flow_reading_table ids from % (attempt % of %), retrying',
                        SQLERRM, tenant_schema, batch_start, attempt, max_attempts;
                    PERFORM pg_sleep(attempt);
                END;
            END LOOP;

            COMMIT;
            filled := filled + batch_rows;
            batch_start := batch_start + batch_size;

            IF batch_rows > 0 THEN
                PERFORM pg_sleep(batch_pause);
            END IF;
        END LOOP;

        -- ── Validate fk_flow_channel ────────────────────────────────────────────────────────────
        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                EXECUTE format('ALTER TABLE %I.flow_reading_table VALIDATE CONSTRAINT fk_flow_channel',
                    tenant_schema);
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V59: lock not available on %.flow_reading_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        finished := finished + 1;

        RAISE NOTICE 'V59: filled channel_id on % row(s) of %.flow_reading_table and validated fk_flow_channel',
            filled, tenant_schema;
    END LOOP;

    RAISE NOTICE 'V59: validated fk_flow_channel in % tenant schema(s)', finished;
END $$;

RESET lock_timeout;
RESET statement_timeout;
