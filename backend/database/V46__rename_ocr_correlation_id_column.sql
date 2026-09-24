-- ============================================================
-- Migration: V46 - Vendor-neutral name for the OCR correlation id
-- ------------------------------------------------------------
-- V32 added a column holding the OCR provider's own correlation id,
-- kept apart from the request id in correlation_id, and named it,
-- its index and its provisioning after the provider. This renames
-- all three:
--
--   flow_reading_table
--     flowvision_correlation_id          -> ocr_correlation_id
--     idx_<schema>_flow_flowvision_corr  -> idx_<schema>_flow_ocr_corr
--
-- RENAME touches the catalogue only, so no table is rewritten, but
-- each rename takes a brief ACCESS EXCLUSIVE lock. lock_timeout stops
-- a rename queued behind a long reader from blocking every reader and
-- writer behind it; a timed-out rename is retried a few times before
-- the migration gives up.
--
-- The .sql.conf beside this file runs it outside Flyway's transaction,
-- so each tenant's renames commit, releasing their locks, before the
-- next tenant's are taken. A failure leaves the tenants before it
-- renamed. Every step skips what is already done, so after
-- `flyway repair` a re-run carries on from the failed tenant.
--
-- telemetry-service reads either name, preferring ocr_correlation_id,
-- so it must be deployed before this runs. It caches whether a column
-- exists, so run this with that cache off (TELEMETRY_CACHE_METADATA_ENABLED=false).
-- ============================================================

-- Session-level: SET LOCAL would not outlive the statement outside a transaction.
SET lock_timeout = '3s';

-- ── Part A: Rename in existing tenant schemas ───────────────────────────────
DO $$
DECLARE
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    old_index     TEXT;
    renames       TEXT[];
    rename_sql    TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        renames := ARRAY[]::TEXT[];

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = tenant_schema
              AND table_name = 'flow_reading_table'
              AND column_name = 'flowvision_correlation_id'
        ) THEN
            renames := renames || format(
                'ALTER TABLE %I.flow_reading_table RENAME COLUMN flowvision_correlation_id TO ocr_correlation_id',
                tenant_schema);
        END IF;

        old_index := format('idx_%s_flow_flowvision_corr', tenant_schema);
        IF to_regclass(format('%I.%I', tenant_schema, old_index)) IS NOT NULL THEN
            renames := renames || format(
                'ALTER INDEX %I.%I RENAME TO %I',
                tenant_schema, old_index, format('idx_%s_flow_ocr_corr', tenant_schema));
        END IF;

        FOREACH rename_sql IN ARRAY renames LOOP
            FOR attempt IN 1..max_attempts LOOP
                BEGIN
                    EXECUTE rename_sql;
                    EXIT;
                EXCEPTION WHEN lock_not_available THEN
                    IF attempt = max_attempts THEN
                        RAISE;
                    END IF;
                    RAISE NOTICE 'V46: lock not available for "%" (attempt % of %), retrying',
                        rename_sql, attempt, max_attempts;
                    PERFORM pg_sleep(attempt);
                END;
            END LOOP;
        END LOOP;

        COMMIT;
    END LOOP;
END $$;

RESET lock_timeout;

-- ── Part B: Ensure new tenant schemas get the new names ─────────────────────
-- V32 appended the provisioning to create_tenant_schema() by patching its source, and V34 then
-- renamed that function to create_tenant_schema_v34_base and wrapped it. Later migrations wrapped
-- it again rather than copying its text, so the old names live in that one function body. It is
-- found by content rather than by name, and patched in place from its full definition. A wrapper
-- would leave every new tenant creating the old column only to rename it.
DO $$
DECLARE
    fn          RECORD;
    patched_def TEXT;
    patched     INT := 0;
BEGIN
    FOR fn IN
        SELECT p.oid, p.proname
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'common_schema'
          AND p.prokind = 'f'
          AND p.prosrc ILIKE '%flowvision%'
    LOOP
        patched_def := replace(replace(replace(
            pg_get_functiondef(fn.oid),
            'flowvision_correlation_id', 'ocr_correlation_id'),
            '_flow_flowvision_corr', '_flow_ocr_corr'),
            '-- FlowVision response correlation id', '-- OCR provider''s response correlation id');

        IF patched_def ILIKE '%flowvision%' THEN
            RAISE EXCEPTION 'V46 patch failed: common_schema.%() names the OCR provider in a form this migration does not rewrite',
                fn.proname;
        END IF;

        EXECUTE patched_def;
        patched := patched + 1;
    END LOOP;

    RAISE NOTICE 'V46: renamed the OCR correlation column in % tenant provisioning function(s)', patched;
END $$;
