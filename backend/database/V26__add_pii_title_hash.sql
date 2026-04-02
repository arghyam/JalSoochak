-- V26: Add title_hash columns for PII-compliant HMAC-SHA256 name lookups.
--
-- title (staff name) is encrypted at the application layer (AES-256-GCM).
-- Exact-match queries (search by name) use the HMAC-SHA256 hash stored in
-- title_hash, keyed by PII_HMAC_KEY (never the DB key).
--
-- Part A: Existing tenant user_tables
-- Part B: Patch create_tenant_schema() so new tenants include the column

-- ── Part A: Existing tenant schemas ─────────────────────────────────────────

DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I.user_table ADD COLUMN IF NOT EXISTS title_hash TEXT',
            tenant_schema);
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_user_title_hash ON %1$I.user_table(title_hash)',
            tenant_schema);
    END LOOP;
END $$;

-- ── Part B: Patch create_tenant_schema() for new tenants ────────────────────
-- Two targeted string replacements (same approach as V25):
--   1. Add title_hash TEXT column to the user_table DDL after phone_number_hash.
--   2. Add the title_hash index EXECUTE line after the phone_hash index line.

DO $$
DECLARE
    func_src     TEXT;
    after_patch1 TEXT;
    after_patch2 TEXT;
BEGIN
    SELECT prosrc INTO func_src
    FROM pg_proc
    WHERE proname = 'create_tenant_schema'
      AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'common_schema');

    -- Patch 1: add title_hash column after phone_number_hash in the user_table DDL
    after_patch1 := replace(
        func_src,
        'phone_number_hash           TEXT,',
        'phone_number_hash           TEXT,' || chr(10) ||
        '            title_hash                  TEXT,'
    );

    IF after_patch1 = func_src THEN
        RAISE EXCEPTION 'V26 patch 1 failed: anchor ''phone_number_hash TEXT,'' not found in create_tenant_schema() body';
    END IF;

    -- Patch 2: add title_hash index EXECUTE line after the phone_hash index line.
    -- We match just the format string content (no surrounding quotes) so no escaping
    -- is needed — the same technique V25 uses for the token-table index.
    after_patch2 := replace(
        after_patch1,
        'CREATE INDEX idx_%1$s_user_phone_hash    ON %1$I.user_table(phone_number_hash)',
        'CREATE INDEX idx_%1$s_user_phone_hash    ON %1$I.user_table(phone_number_hash)' ||
        chr(39) || ',  schema_name);' || chr(10) ||
        '    EXECUTE format(' || chr(39) ||
        'CREATE INDEX idx_%1$s_user_title_hash    ON %1$I.user_table(title_hash)'
    );

    IF after_patch2 = after_patch1 THEN
        RAISE EXCEPTION 'V26 patch 2 failed: anchor ''idx_%%1$s_user_phone_hash'' not found in create_tenant_schema() body';
    END IF;

    EXECUTE 'CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT) '
         || 'RETURNS VOID LANGUAGE plpgsql AS '
         || chr(36) || 'body' || chr(36)
         || after_patch2
         || chr(36) || 'body' || chr(36);
END $$;
