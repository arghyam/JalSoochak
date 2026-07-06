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

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'create_tenant_schema'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE p.proname = 'create_tenant_schema_v32_base'
          AND n.nspname = 'common_schema'
          AND pg_get_function_identity_arguments(p.oid) = 'schema_name text'
    ) THEN
        ALTER FUNCTION common_schema.create_tenant_schema(text) RENAME TO create_tenant_schema_v32_base;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS VOID
LANGUAGE plpgsql
AS $func$
BEGIN
    PERFORM common_schema.create_tenant_schema_v32_base(schema_name);

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
END;
$func$;
