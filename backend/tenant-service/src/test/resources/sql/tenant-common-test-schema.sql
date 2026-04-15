-- =============================================================
-- Test schema for TenantCommonRepository integration tests.
-- Created without FK constraints for fast, isolated setup.
-- Uses postgres:16-alpine which ships with gen_random_uuid() built-in.
-- =============================================================

CREATE SCHEMA IF NOT EXISTS common_schema;

CREATE TABLE common_schema.tenant_master_table (
    id           SERIAL,
    uuid         VARCHAR(36)  NOT NULL DEFAULT gen_random_uuid()::TEXT,
    state_code   VARCHAR(10)  NOT NULL,
    lgd_code     INTEGER      NOT NULL DEFAULT 0,
    title        VARCHAR(255) NOT NULL DEFAULT '',
    status       INTEGER      NOT NULL DEFAULT 1,
    created_at   TIMESTAMP,
    created_by   INTEGER,
    onboarded_at TIMESTAMP,
    updated_at   TIMESTAMP,
    updated_by   INTEGER,
    deleted_at   TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (state_code)
);

-- System tenant sentinel (id=0 is a special non-SERIAL value).
-- SERIAL columns still accept explicit values; the sequence is unaffected and
-- continues to start at 1 for subsequently auto-generated rows.
INSERT INTO common_schema.tenant_master_table
    (id, state_code, title, status, lgd_code)
VALUES (0, 'SYS', 'System', 0, 0);

CREATE TABLE common_schema.tenant_config_master_table (
    id           SERIAL PRIMARY KEY,
    uuid         VARCHAR(36)  NOT NULL DEFAULT gen_random_uuid()::TEXT,
    tenant_id    INTEGER      NOT NULL,
    config_key   TEXT,
    config_value TEXT,
    created_at   TIMESTAMP,
    created_by   INTEGER,
    updated_at   TIMESTAMP,
    updated_by   INTEGER,
    deleted_at   TIMESTAMP
);

-- Partial unique index required by the upsert ON CONFLICT clause in upsertConfig()
CREATE UNIQUE INDEX uq_tenant_config_key
    ON common_schema.tenant_config_master_table (tenant_id, config_key)
    WHERE deleted_at IS NULL;

CREATE TABLE common_schema.tenant_admin_user_master_table (
    id   SERIAL PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL DEFAULT gen_random_uuid()::TEXT
);

-- Stub function: create_tenant_schema() is a no-op in tests.
-- The real implementation provisions all tenant-schema tables via Flyway (V2 migration).
CREATE OR REPLACE FUNCTION common_schema.create_tenant_schema(schema_name TEXT)
RETURNS void AS $$
BEGIN
    -- no-op stub for testing
END;
$$ LANGUAGE plpgsql;
