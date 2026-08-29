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

DO $$
DECLARE
    tenant_record RECORD;
    unresolved_count INTEGER;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'common_schema'
          AND table_name = 'user_channel_preference'
          AND column_name = 'contact_id'
    ) THEN
        FOR tenant_record IN
            SELECT id, 'tenant_' || lower(trim(state_code)) AS schema_name
            FROM common_schema.tenant_master_table
            WHERE state_code IS NOT NULL
        LOOP
            IF EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = tenant_record.schema_name
                  AND table_name = 'user_table'
            )
            AND EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = tenant_record.schema_name
                  AND table_name = 'user_scheme_mapping_table'
            )
            AND EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = tenant_record.schema_name
                  AND table_name = 'scheme_master_table'
            ) THEN
                EXECUTE format($sql$
                    WITH ranked_scheme AS (
                        SELECT
                            ucp.id AS preference_id,
                            usm.scheme_id,
                            ROW_NUMBER() OVER (
                                PARTITION BY ucp.id
                                ORDER BY usm.id
                            ) AS rn
                        FROM common_schema.user_channel_preference ucp
                        JOIN %1$I.user_table u
                          ON u.tenant_id = ucp.tenant_id
                         AND (
                                u.phone_number = ucp.contact_id::text
                             OR regexp_replace(COALESCE(u.phone_number, ''), '\D', '', 'g')
                                = regexp_replace(COALESCE(ucp.contact_id::text, ''), '\D', '', 'g')
                         )
                        JOIN %1$I.user_scheme_mapping_table usm
                          ON usm.user_id = u.id
                         AND usm.status = 1
                         AND usm.deleted_at IS NULL
                        JOIN %1$I.scheme_master_table sm
                          ON sm.id = usm.scheme_id
                         AND sm.deleted_at IS NULL
                        WHERE ucp.tenant_id = $1
                          AND ucp.scheme_id IS NULL
                          AND ucp.contact_id IS NOT NULL
                    )
                    UPDATE common_schema.user_channel_preference ucp
                       SET scheme_id = ranked_scheme.scheme_id,
                           updated_at = NOW()
                      FROM ranked_scheme
                     WHERE ranked_scheme.preference_id = ucp.id
                       AND ranked_scheme.rn = 1
                $sql$, tenant_record.schema_name)
                USING tenant_record.id;
            END IF;
        END LOOP;
    END IF;

    WITH ranked_duplicates AS (
        SELECT
            id,
            ROW_NUMBER() OVER (
                PARTITION BY tenant_id, scheme_id
                ORDER BY updated_at DESC NULLS LAST, id DESC
            ) AS rn
        FROM common_schema.user_channel_preference
        WHERE scheme_id IS NOT NULL
    )
    DELETE FROM common_schema.user_channel_preference ucp
    USING ranked_duplicates
    WHERE ranked_duplicates.id = ucp.id
      AND ranked_duplicates.rn > 1;

    SELECT COUNT(*)
    INTO unresolved_count
    FROM common_schema.user_channel_preference
    WHERE scheme_id IS NULL;

    IF unresolved_count > 0 THEN
        RAISE EXCEPTION 'Unable to backfill scheme_id for % user_channel_preference row(s)', unresolved_count;
    END IF;
END $$;

ALTER TABLE common_schema.user_channel_preference
    ALTER COLUMN scheme_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_channel_pref_scheme
    ON common_schema.user_channel_preference (tenant_id, scheme_id);

CREATE INDEX IF NOT EXISTS idx_user_channel_pref_tenant_scheme
    ON common_schema.user_channel_preference (tenant_id, scheme_id);
