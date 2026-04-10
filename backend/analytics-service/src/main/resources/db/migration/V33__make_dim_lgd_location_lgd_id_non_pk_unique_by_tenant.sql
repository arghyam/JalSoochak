-- Make dim_lgd_location_table.lgd_id non-PK and enforce uniqueness per tenant.

-- 1) Drop PK on lgd_id so lgd_id is non-primary / non-unique
ALTER TABLE analytics_schema.dim_lgd_location_table
    DROP CONSTRAINT IF EXISTS dim_lgd_location_table_pkey;

-- 2) Enforce UNIQUE(lgd_id, tenant_id)
CREATE UNIQUE INDEX IF NOT EXISTS ux_dim_lgd_location_lgd_id_tenant_id
    ON analytics_schema.dim_lgd_location_table (lgd_id, tenant_id);

