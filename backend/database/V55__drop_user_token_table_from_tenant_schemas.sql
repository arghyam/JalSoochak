-- ============================================================
-- Migration: V55 - Drop user_token_table from the tenant schemas
-- ------------------------------------------------------------
-- V17 turned each tenant's user_invite_table into user_token_table, for
-- invite and password-reset tokens. No service has ever read or written
-- it: those tokens live in common_schema.admin_user_token_table (V16),
-- staff login OTPs in common_schema.otp_table, and refresh tokens in
-- Keycloak. This drops it, with its indexes and sequence, from every
-- tenant schema and from the provisioning of new ones.
--
-- The table has a foreign key to the tenant's user_table, so dropping it
-- also takes an ACCESS EXCLUSIVE lock on user_table, to remove the
-- triggers enforcing that key. user_table is in constant use, so, as in
-- V46, lock_timeout stops a drop queued behind a long reader from
-- blocking every reader and writer behind it; a timed-out drop is retried
-- a few times before the migration gives up.
--
-- Provisioning is patched first, and provisioning already running the
-- unpatched function is waited out, before the tenants to drop from are
-- listed, so no tenant can be created with the table unseen.
--
-- The .sql.conf beside this file runs it outside Flyway's transaction,
-- so each tenant's drop commits, releasing its locks, before the next
-- tenant's is taken. A failure leaves the tenants before it dropped.
-- Every step skips what is already done, so after `flyway repair` a
-- re-run carries on from the failed tenant.
-- ============================================================

-- ── Part A: Stop new tenant schemas getting the table ───────────────────────
-- V30 rewrote create_tenant_schema() in full, creating the table and its indexes in its body, and
-- V31 renamed that function to create_tenant_schema_v31_base. Every later migration wrapped it, so
-- the chain is walked from create_tenant_schema() and that body patched in place from its full
-- definition, as in V52. A wrapper would leave every new tenant creating the table only to drop it.
--
-- V17, V23 and V27 also created the table, but their functions stopped being called when V30
-- replaced the body they sit under, so they are left as they are.
DO $$
DECLARE
    fn_name     TEXT := 'create_tenant_schema';
    fn_oid      OID;
    fn_src      TEXT;
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
            RAISE EXCEPTION 'V55: common_schema.%(text) is called by the create_tenant_schema() chain but does not exist',
                fn_name;
        END IF;

        IF strpos(fn_src, 'user_token_table') > 0 THEN
            -- Removes the CREATE TABLE statement, then the commented block of its four indexes, each
            -- with the blank line before it. [^'] keeps a match inside the format string it starts in.
            patched_def := regexp_replace(regexp_replace(
                pg_get_functiondef(fn_oid),
                '\n\n[ \t]*EXECUTE format\(''[^'']*\.user_token_table \([^'']*'',\s*schema_name\);', ''),
                '\n\n[ \t]*-- user_token_table\n([ \t]*EXECUTE format\(''[^'']*\.user_token_table\([^'']*'',\s*schema_name\);\n)+',
                E'\n');

            IF strpos(patched_def, 'user_token_table') > 0 THEN
                RAISE EXCEPTION 'V55 patch failed: common_schema.%() creates user_token_table in a form this migration does not remove',
                    fn_name;
            END IF;

            EXECUTE patched_def;
            patched := patched + 1;
        END IF;

        -- Each wrapper runs the function it wraps first; the chain ends at a body with no such call.
        fn_name := substring(fn_src FROM 'PERFORM\s+common_schema\.(create_tenant_schema_[a-z0-9_]+)\s*\(');
        EXIT WHEN fn_name IS NULL;
    END LOOP;

    -- Patching nothing is not an error: a re-run after a failure in Part C finds this already done.
    RAISE NOTICE 'V55: removed the table from % tenant provisioning function(s)', patched;
END $$;

-- ── Part B: Wait out provisioning already running ───────────────────────────
-- A create_tenant_schema() call that began before Part A committed runs the unpatched function, and
-- Part C cannot see its schema until it commits. Every call runs inside the transaction of
-- TenantManagementServiceImpl.createTenant, which writes tenant_master_table first, so a SHARE lock
-- on that table waits for each such call to commit. It holds up only writers to that table, not
-- readers, so it is taken before lock_timeout is set, and released as soon as it is granted.
DO $$
BEGIN
    LOCK TABLE common_schema.tenant_master_table IN SHARE MODE;
END $$;

-- Session-level: SET LOCAL would not outlive the statement outside a transaction.
SET lock_timeout = '3s';

-- ── Part C: Drop from existing tenant schemas ───────────────────────────────
-- Its indexes, sequence and foreign key go with the table. The drop is RESTRICT, so anything else
-- found depending on the table fails the migration rather than being dropped with it.
DO $$
DECLARE
    max_attempts  CONSTANT INT := 5;
    tenant_schema TEXT;
    dropped       INT := 0;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
        ORDER BY nspname
    LOOP
        CONTINUE WHEN to_regclass(format('%I.user_token_table', tenant_schema)) IS NULL;

        FOR attempt IN 1..max_attempts LOOP
            BEGIN
                EXECUTE format('DROP TABLE %I.user_token_table', tenant_schema);
                EXIT;
            EXCEPTION WHEN lock_not_available THEN
                IF attempt = max_attempts THEN
                    RAISE;
                END IF;
                RAISE NOTICE 'V55: lock not available dropping %.user_token_table (attempt % of %), retrying',
                    tenant_schema, attempt, max_attempts;
                PERFORM pg_sleep(attempt);
            END;
        END LOOP;

        COMMIT;
        dropped := dropped + 1;
    END LOOP;

    RAISE NOTICE 'V55: dropped user_token_table from % tenant schema(s)', dropped;
END $$;

RESET lock_timeout;
