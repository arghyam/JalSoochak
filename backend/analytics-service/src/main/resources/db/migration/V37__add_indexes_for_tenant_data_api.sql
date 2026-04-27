-- Indexes to speed up /api/v1/analytics/tenant_data queries (scoping + joins).

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_level_1_lgd_id
    ON analytics_schema.dim_scheme_table (tenant_id, level_1_lgd_id);

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_level_2_lgd_id
    ON analytics_schema.dim_scheme_table (tenant_id, level_2_lgd_id);

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_level_3_lgd_id
    ON analytics_schema.dim_scheme_table (tenant_id, level_3_lgd_id);

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_level_1_dept_id
    ON analytics_schema.dim_scheme_table (tenant_id, level_1_dept_id);

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_level_2_dept_id
    ON analytics_schema.dim_scheme_table (tenant_id, level_2_dept_id);

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_level_3_dept_id
    ON analytics_schema.dim_scheme_table (tenant_id, level_3_dept_id);

CREATE INDEX IF NOT EXISTS idx_dim_scheme_tenant_scheme_id
    ON analytics_schema.dim_scheme_table (tenant_id, scheme_id);

CREATE INDEX IF NOT EXISTS idx_fact_meter_reading_tenant_scheme_id
    ON analytics_schema.fact_meter_reading_table (tenant_id, scheme_id);

CREATE INDEX IF NOT EXISTS idx_fact_scheme_performance_scheme_tenant
    ON analytics_schema.fact_scheme_performance_table (scheme_id, tenant_id);

