-- ============================================================
-- Migration: V63 - Repurpose notification_table as the outbound delivery ledger
-- ------------------------------------------------------------
--   common_schema.channel_master_table
--     + rows 8 EMAIL, 9 SMS  (channel_type 2; 6 WHATSAPP exists since V61)
--   common_schema.apply_notification_ledger_shape(text)   (new)
--     Brings <schema>.notification_table to the ledger shape. Idempotent.
--   common_schema.notification_table                      (new)
--     The same table for sends that belong to no tenant (super-user invites
--     and password resets).
--   common_schema.notification_status_sync_state          (new)
--     Where each delivery-status pull keeps its cursor.
--   <tenant>.notification_table
--     user_id, message_blob      NOT NULL dropped
--     message_blob               TEXT -> JSONB
--     channel                    + key -> channel_master_table(id)
--     + delivery columns, checks and indexes (see the function below)
--
-- notification_table was provisioned in every tenant schema by V2 and never
-- written by any service. From this migration message-service writes one row
-- per outbound message per recipient: what was sent, through which channel
-- and provider, what the provider answered at send time, and the delivery
-- status the provider reports later. Provider names are stored as the
-- identifier the sending adapter reports, never constrained here, so adding a
-- provider needs no migration.
--
-- No phone number, email address, name or message text is stored: recipients
-- are held as a user id and an HMAC of the address, and message_blob holds
-- only send metadata. seen_status is left as it was, for an in-app inbox; the
-- ledger never sets it.
--
-- The table is retyped and its columns tightened in place, which needs it to
-- be empty: a schema whose table has rows fails the migration rather than
-- losing or mis-reading them.
--
-- Provisioning is patched first, and provisioning already running the
-- unpatched function is waited out, before the tenants to change are listed,
-- so no tenant can be created with the old shape unseen.
--
-- The .sql.conf beside this file runs it outside Flyway's transaction, so
-- each schema's change commits, releasing its lock, before the next one's is
-- taken. Every step skips what is already done, so after `flyway repair` a
-- re-run carries on from the failed schema.
-- ============================================================

-- ── Part A: Seed the notification channels ──────────────────────────────────
-- As in V58 and V61, the ids are given rather than drawn from the sequence, since they are the codes
-- the sending service uses. A row an environment already has under one of these ids or titles is kept,
-- and the check that follows fails the migration unless each id carries its channel's title and type.
INSERT INTO common_schema.channel_master_table (id, title, channel_type)
VALUES (8, 'EMAIL', 2),
       (9, 'SMS', 2)
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
    FROM (VALUES (6, 'WHATSAPP'), (8, 'EMAIL'), (9, 'SMS')) AS expected(id, title)
    LEFT JOIN common_schema.channel_master_table m ON m.id = expected.id
    WHERE m.title IS DISTINCT FROM expected.title
       OR m.channel_type IS DISTINCT FROM 2;

    IF mismatches IS NOT NULL THEN
        RAISE EXCEPTION 'V63: common_schema.channel_master_table does not hold the notification channels under the ids the sending service uses: %',
            mismatches;
    END IF;
END $$;

-- So that ids drawn from the sequence start after the seeded ones.
SELECT setval(pg_get_serial_sequence('common_schema.channel_master_table', 'id'),
              (SELECT max(id) FROM common_schema.channel_master_table));

-- ── Part B: The ledger shape ────────────────────────────────────────────────
-- One definition, applied to every tenant's table, to the platform table below, and by the wrapper in
-- Part D to each tenant created from now on. Every step checks what is already there, so applying it
-- twice changes nothing. It refuses a table with rows: retyping message_blob and adding the NOT NULL
-- columns are only safe on an empty one.
CREATE OR REPLACE FUNCTION common_schema.apply_notification_ledger_shape(target_schema TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
DECLARE
    tbl     REGCLASS := to_regclass(format('%I.notification_table', target_schema));
    has_row BOOLEAN;
BEGIN
    IF tbl IS NULL THEN
        RETURN;
    END IF;

    -- The shape is complete once the last column below exists; nothing else needs checking then.
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid = tbl AND attname = 'status_version' AND NOT attisdropped) THEN
        RETURN;
    END IF;

    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.notification_table)', target_schema) INTO has_row;
    IF has_row THEN
        RAISE EXCEPTION 'V63: %.notification_table has rows; it was expected never to have been written',
            target_schema;
    END IF;

    EXECUTE format(
        'ALTER TABLE %1$I.notification_table
             ALTER COLUMN user_id DROP NOT NULL,
             ALTER COLUMN message_blob DROP NOT NULL,
             ALTER COLUMN message_blob TYPE JSONB USING message_blob::JSONB,
             ADD COLUMN IF NOT EXISTS admin_user_id          BIGINT,
             ADD COLUMN IF NOT EXISTS user_type              VARCHAR(40),
             ADD COLUMN IF NOT EXISTS recipient_hash         VARCHAR(64),
             ADD COLUMN IF NOT EXISTS event_type             VARCHAR(60),
             ADD COLUMN IF NOT EXISTS provider               VARCHAR(40)  NOT NULL,
             ADD COLUMN IF NOT EXISTS provider_contact_ref   VARCHAR(64),
             ADD COLUMN IF NOT EXISTS template_ref           VARCHAR(64),
             ADD COLUMN IF NOT EXISTS correlation_id         VARCHAR(64),
             ADD COLUMN IF NOT EXISTS subject_date           DATE,
             ADD COLUMN IF NOT EXISTS dedupe_key             VARCHAR(160),
             ADD COLUMN IF NOT EXISTS dispatch_status        VARCHAR(32)  NOT NULL,
             ADD COLUMN IF NOT EXISTS failure_stage          VARCHAR(32),
             ADD COLUMN IF NOT EXISTS delivery_status        VARCHAR(20)  NOT NULL DEFAULT ''PENDING'',
             ADD COLUMN IF NOT EXISTS provider_message_id    VARCHAR(128),
             ADD COLUMN IF NOT EXISTS provider_status        VARCHAR(40),
             ADD COLUMN IF NOT EXISTS provider_error_code    VARCHAR(64),
             ADD COLUMN IF NOT EXISTS provider_error_message VARCHAR(500),
             ADD COLUMN IF NOT EXISTS provider_cost          NUMERIC(10, 4),
             ADD COLUMN IF NOT EXISTS provider_cost_currency VARCHAR(8),
             ADD COLUMN IF NOT EXISTS latency_ms             INTEGER,
             ADD COLUMN IF NOT EXISTS dispatched_at          TIMESTAMP,
             ADD COLUMN IF NOT EXISTS delivered_at           TIMESTAMP,
             ADD COLUMN IF NOT EXISTS read_at                TIMESTAMP,
             ADD COLUMN IF NOT EXISTS status_updated_at      TIMESTAMP,
             ADD COLUMN IF NOT EXISTS delivery_settled_at    TIMESTAMP,
             ADD COLUMN IF NOT EXISTS status_check_count     SMALLINT     NOT NULL DEFAULT 0,
             ADD COLUMN IF NOT EXISTS status_version         INTEGER      NOT NULL DEFAULT 0',
        target_schema);

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = tbl AND conname = 'fk_notification_channel') THEN
        EXECUTE format(
            'ALTER TABLE %I.notification_table
                 ADD CONSTRAINT fk_notification_channel FOREIGN KEY (channel)
                     REFERENCES common_schema.channel_master_table(id)',
            target_schema);
    END IF;

    -- Mirrors DispatchStatus in message-service: what became of our attempt to hand the message over.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = tbl AND conname = 'chk_notification_dispatch_status') THEN
        EXECUTE format(
            'ALTER TABLE %I.notification_table
                 ADD CONSTRAINT chk_notification_dispatch_status CHECK (dispatch_status IN (
                     ''DISPATCHING'', ''ACCEPTED'', ''SUPPRESSED'', ''PROVIDER_REJECTED'', ''FAILED_DELIVERY'',
                     ''DELIVERY_UNCONFIRMED'', ''FAILED_GENERATION'', ''FAILED_UPLOAD'', ''SKIPPED_NO_CONTACT''))',
            target_schema);
    END IF;

    -- Mirrors DeliveryState in message-service: what the provider later reports happened to it.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = tbl AND conname = 'chk_notification_delivery_status') THEN
        EXECUTE format(
            'ALTER TABLE %I.notification_table
                 ADD CONSTRAINT chk_notification_delivery_status CHECK (delivery_status IN (
                     ''PENDING'', ''DELIVERED'', ''READ'', ''FAILED'', ''UNRESOLVED'', ''NOT_TRACKED'', ''NOT_SENT''))',
            target_schema);
    END IF;

    -- Time scans and the retention purge.
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_notif_created_at ON %I.notification_table (created_at)',
        target_schema);
    -- The join key for a delivery status. Unique, so a status can never land on two rows.
    EXECUTE format(
        'CREATE UNIQUE INDEX IF NOT EXISTS uq_notif_provider_message
             ON %I.notification_table (provider, provider_message_id)
             WHERE provider_message_id IS NOT NULL',
        target_schema);
    -- The status pull's work-list: only rows still waiting, which stay a small part of the table.
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_notif_pending
             ON %I.notification_table (channel, created_at)
             WHERE delivery_status = ''PENDING''',
        target_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_notif_dedupe_key
             ON %I.notification_table (dedupe_key)
             WHERE dedupe_key IS NOT NULL',
        target_schema);
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS idx_notif_type_subject_date
             ON %I.notification_table (message_type, subject_date)',
        target_schema);

    EXECUTE format('COMMENT ON TABLE %I.notification_table IS %L', target_schema,
        'Outbound delivery ledger, written by message-service since V63: one row per message per recipient, '
        || 'with the provider''s answer at send time and the delivery status it reports later. Holds no '
        || 'phone number, email address, name or message text.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.user_id IS %L', target_schema,
        'The recipient''s user_table id, or NULL for an admin user, a pending invitee or a phone that '
        || 'matched no user.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.message IS %L', target_schema,
        'Unused by the ledger, which stores no message text. Always NULL.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.seen_status IS %L', target_schema,
        'Reserved for an in-app inbox. The ledger never sets it.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.channel IS %L', target_schema,
        'common_schema.channel_master_table id: 6 WHATSAPP, 8 EMAIL, 9 SMS.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.message_blob IS %L', target_schema,
        'Send metadata only (delivery mode, template, link without query string, report dates). Never a '
        || 'name, phone number, email address or OTP.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.message_type IS %L', target_schema,
        'NotificationType: NUDGE, ESCALATION, DAILY_REPORT, WEEKLY_REPORT, WELCOME, LOGIN_OTP, INVITE, '
        || 'REINVITE, PASSWORD_RESET.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.admin_user_id IS %L', target_schema,
        'Logical key to common_schema.tenant_admin_user_master_table(id) for a recipient who is an admin user.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.recipient_hash IS %L', target_schema,
        'HMAC-SHA256 of the normalised phone number or email address, the same keyed hash as '
        || 'user_table.phone_number_hash. Never the address itself.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.provider IS %L', target_schema,
        'The identifier the sending adapter reports for its provider. Free text, so a new provider needs no '
        || 'migration.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.provider_contact_ref IS %L', target_schema,
        'The provider''s own id for the recipient, e.g. user_table.whatsapp_connection_id. Not an address.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.subject_date IS %L', target_schema,
        'The day the content is about: the report date, the first day of a reported week, a nudge''s date.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.dedupe_key IS %L', target_schema,
        'message_type:recipient:subject_date. Recorded only; nothing enforces it yet.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.dispatch_status IS %L', target_schema,
        'What became of handing the message over (DispatchStatus). ACCEPTED means the provider took it, '
        || 'not that it was delivered.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.delivery_status IS %L', target_schema,
        'What the provider reports happened afterwards (DeliveryState). Moves forward only.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.provider_status IS %L', target_schema,
        'The provider''s own status word, verbatim.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.provider_error_message IS %L', target_schema,
        'The provider''s failure text with phone numbers redacted, truncated to 500 characters.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.latency_ms IS %L', target_schema,
        'Milliseconds from opening the row to the provider''s answer; for a report this includes building '
        || 'and uploading it.');
    EXECUTE format('COMMENT ON COLUMN %I.notification_table.status_version IS %L', target_schema,
        'Incremented on every change, so a consumer of the change feed can discard an older snapshot.');
END;
$func$;

-- ── Part C: The tables in common_schema ─────────────────────────────────────
-- For sends that belong to no tenant. The tenant table's shape before Part B, without the key to a
-- user_table that common_schema does not have, so that the one function above shapes both.
CREATE TABLE IF NOT EXISTS common_schema.notification_table (
    id              SERIAL          PRIMARY KEY,
    uuid            VARCHAR(36)     NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    user_id         INTEGER         NOT NULL,
    message         TEXT,
    seen_status     BOOLEAN         NOT NULL DEFAULT FALSE,
    channel         INTEGER         NOT NULL,
    message_blob    TEXT            NOT NULL,
    message_type    VARCHAR(50)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    deleted_by      INTEGER
);

SELECT common_schema.apply_notification_ledger_shape('common_schema');

CREATE TABLE IF NOT EXISTS common_schema.notification_status_sync_state (
    source      VARCHAR(64)     PRIMARY KEY,
    cursor_at   TIMESTAMP       NOT NULL,
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE common_schema.notification_status_sync_state IS
    'One row per delivery-status pull: the provider time up to which status changes have been read. Advanced only after a pass completes, so a failed pass is read again.';

-- ── Part D: Ensure new tenant schemas get the ledger shape ──────────────────
-- Wrapper pattern, as in V58 and V61: the current function is copied under a versioned name, and the
-- wrapper replaces it in one statement, so no tenant is created between the two with no
-- create_tenant_schema().
DO $$
BEGIN
    IF to_regprocedure('common_schema.create_tenant_schema_v63_base(text)') IS NULL THEN
        EXECUTE replace(
            pg_get_functiondef('common_schema.create_tenant_schema(text)'::regprocedure),
            'FUNCTION common_schema.create_tenant_schema(',
            'FUNCTION common_schema.create_tenant_schema_v63_base(');
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    -- Execute the existing provisioning logic first.
    PERFORM common_schema.create_tenant_schema_v63_base(schema_name);

    -- A no-op on a partially-provisioned schema with no notification_table.
    PERFORM common_schema.apply_notification_ledger_shape(schema_name);
END;
$func$;

-- ── Part E: Wait out provisioning already running ───────────────────────────
-- As in V61: every create_tenant_schema() call runs inside the transaction of
-- TenantManagementServiceImpl.createTenant, which writes tenant_master_table first, so a SHARE lock on
-- that table waits for each call that began before Part D committed.
DO $$
BEGIN
    LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;
END $$;

SET statement_timeout = 0;
SET lock_timeout = '3s';

-- ── Part F: notification_table in existing tenant schemas ───────────────────
-- Each schema's change commits on its own. The table is never read or written by anything yet, so
-- its ACCESS EXCLUSIVE lock is uncontended; the retry covers the brief lock on channel_master_table
-- that adding the key takes.
DO $$
DECLARE
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    changed       INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        CONTINUE WHEN to_regclass(format('%I.notification_table', tenant_schema)) IS NULL;
        CONTINUE WHEN EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = to_regclass(format('%I.notification_table', tenant_schema))
              AND attname = 'status_version'
              AND NOT attisdropped);

        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                PERFORM common_schema.apply_notification_ledger_shape(tenant_schema);
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V63: lock not available on %.notification_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        changed := changed + 1;
    END LOOP;

    RAISE NOTICE 'V63: reshaped notification_table as the delivery ledger in % tenant schema(s)', changed;
END $$;

RESET lock_timeout;
RESET statement_timeout;
