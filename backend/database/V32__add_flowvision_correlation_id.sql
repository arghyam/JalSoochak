-- Add FlowVision's returned result.correlationId separately from the request id
-- stored in flow_reading_table.correlation_id.

DO $$
DECLARE
    tenant_schema TEXT;
BEGIN
    FOR tenant_schema IN
        SELECT nspname FROM pg_namespace WHERE nspname LIKE 'tenant\_%' ESCAPE '\'
    LOOP
        IF to_regclass(format('%I.flow_reading_table', tenant_schema)) IS NOT NULL THEN
            EXECUTE format(
                'ALTER TABLE %1$I.flow_reading_table
                     ADD COLUMN IF NOT EXISTS flowvision_correlation_id VARCHAR(255)',
                tenant_schema);
            EXECUTE format(
                'CREATE INDEX IF NOT EXISTS idx_%1$s_flow_flowvision_corr
                     ON %1$I.flow_reading_table(flowvision_correlation_id)',
                tenant_schema);
        END IF;
    END LOOP;
END $$;

-- Patch the current create_tenant_schema() body in place instead of adding another
-- versioned wrapper. Wrapper chains are easy to clobber in later full replacements.
DO $$
DECLARE
    func_src         TEXT;
    patched_src      TEXT;
    flowvision_patch TEXT := $patch$

    -- FlowVision response correlation id for new tenant schemas.
    IF to_regclass(format('%I.flow_reading_table', schema_name)) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %1$I.flow_reading_table
                 ADD COLUMN IF NOT EXISTS flowvision_correlation_id VARCHAR(255)',
            schema_name);
        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS idx_%1$s_flow_flowvision_corr
                 ON %1$I.flow_reading_table(flowvision_correlation_id)',
            schema_name);
    END IF;
$patch$;
BEGIN
    SELECT p.prosrc
    INTO func_src
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE p.proname = 'create_tenant_schema'
      AND n.nspname = 'common_schema'
      AND pg_get_function_identity_arguments(p.oid) = 'schema_name text';

    IF func_src IS NULL THEN
        RAISE EXCEPTION 'V32 patch failed: function common_schema.create_tenant_schema(schema_name text) not found';
    END IF;

    IF position('flowvision_correlation_id' IN func_src) > 0 THEN
        RAISE NOTICE 'V32: create_tenant_schema() already includes flowvision_correlation_id. Skipping function patch.';
        RETURN;
    END IF;

    patched_src := regexp_replace(
        func_src,
        E'\nEND;[[:space:]]*$',
        flowvision_patch || E'\nEND;'
    );

    IF patched_src = func_src THEN
        RAISE EXCEPTION 'V32 patch failed: could not append flowvision_correlation_id provisioning to create_tenant_schema()';
    END IF;

    EXECUTE 'CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT) '
         || 'RETURNS VOID LANGUAGE plpgsql AS '
         || chr(36) || 'body' || chr(36)
         || patched_src
         || chr(36) || 'body' || chr(36);
END $$;
