-- V28: Add api_key_hash column to common_schema.tenant_master_table
-- Stores the SHA-256 hash of the tenant's API key (raw key is shown once and never persisted).
-- Unique index enables O(1) tenant lookup by incoming API key.

ALTER TABLE common_schema.tenant_master_table
    ADD COLUMN IF NOT EXISTS api_key_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_tenant_api_key_hash
    ON common_schema.tenant_master_table (api_key_hash)
    WHERE api_key_hash IS NOT NULL;
