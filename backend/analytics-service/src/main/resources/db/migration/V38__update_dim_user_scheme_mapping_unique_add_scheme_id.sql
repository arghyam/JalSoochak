-- Extend the composite unique constraint to include scheme_id.
-- A user may be mapped to the same scheme under multiple tenants but should
-- not appear twice for the same (user_id, uuid, tenant_id, scheme_id) tuple.

ALTER TABLE analytics_schema.dim_user_scheme_mapping_table
    DROP CONSTRAINT IF EXISTS uq_dim_user_scheme_mapping_user_uuid_tenant;

ALTER TABLE analytics_schema.dim_user_scheme_mapping_table
    ADD CONSTRAINT uq_dim_user_scheme_mapping_user_uuid_tenant
    UNIQUE (user_id, uuid, tenant_id, scheme_id);
