-- Make (user_id, uuid, tenant_id) unique instead of uuid alone.

-- Drop the implicit unique constraint created by: uuid UUID NOT NULL UNIQUE
ALTER TABLE analytics_schema.dim_user_scheme_mapping_table
    DROP CONSTRAINT IF EXISTS dim_user_scheme_mapping_table_uuid_key;

-- Add composite uniqueness.
ALTER TABLE analytics_schema.dim_user_scheme_mapping_table
    ADD CONSTRAINT uq_dim_user_scheme_mapping_user_uuid_tenant
    UNIQUE (user_id, uuid, tenant_id);

