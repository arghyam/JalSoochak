-- ============================================================
-- Migration: V31 - Map user channel preference by scheme
-- ============================================================

CREATE TABLE IF NOT EXISTS common_schema.user_channel_preference (
    id              BIGSERIAL       PRIMARY KEY,
    tenant_id       INTEGER         NOT NULL,
    scheme_id       BIGINT          NOT NULL,
    channel_value   VARCHAR(100)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

ALTER TABLE common_schema.user_channel_preference
    ADD COLUMN IF NOT EXISTS scheme_id BIGINT;

ALTER TABLE common_schema.user_channel_preference
    ADD COLUMN IF NOT EXISTS channel_value VARCHAR(100);

ALTER TABLE common_schema.user_channel_preference
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();

ALTER TABLE common_schema.user_channel_preference
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'common_schema'
          AND table_name = 'user_channel_preference'
          AND column_name = 'contact_id'
    ) THEN
        ALTER TABLE common_schema.user_channel_preference
            ALTER COLUMN contact_id DROP NOT NULL;
    END IF;
END $$;

ALTER TABLE common_schema.user_channel_preference
    DROP CONSTRAINT IF EXISTS uq_user_channel_pref;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_channel_pref_scheme
    ON common_schema.user_channel_preference (tenant_id, scheme_id);

CREATE INDEX IF NOT EXISTS idx_user_channel_pref_tenant_scheme
    ON common_schema.user_channel_preference (tenant_id, scheme_id);
