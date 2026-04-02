package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TenantDepartmentBoundaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public Integer getDepartmentLevel(Integer tenantId, Integer departmentId) {
        String sql = """
                SELECT department_level
                FROM analytics_schema.dim_department_location_table
                WHERE tenant_id = ?
                  AND department_id = ?
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> (Integer) rs.getObject("department_level"), tenantId, departmentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> getChildDepartmentsByParent(Integer tenantId, Integer parentDepartmentId, Integer parentLevel) {
        int childLevel = parentLevel + 1;
        String parentLevelColumn = getDeptLevelColumn(parentLevel);
        String childLevelSchemeColumn = getDeptLevelColumn(childLevel);

        String sql = String.format("""
                SELECT
                    d.department_id AS department_id,
                    ?::int AS parent_department_id,
                    d.department_level AS child_level,
                    (
                        SELECT COUNT(*)::int
                        FROM analytics_schema.dim_scheme_table s
                        WHERE s.tenant_id = ?
                          AND s.%2$s = d.department_id
                    ) AS scheme_count,
                    d.title AS title,
                    NULL::varchar AS lgd_code,
                    ST_AsGeoJSON(d.geom, 9, 8) AS boundary_geojson
                FROM analytics_schema.dim_department_location_table d
                WHERE d.tenant_id = ?
                  AND d.department_level = ?
                  AND d.%1$s = ?
                ORDER BY d.department_id
                """, parentLevelColumn, childLevelSchemeColumn);

        return jdbcTemplate.queryForList(sql, parentDepartmentId, tenantId, tenantId, childLevel, parentDepartmentId);
    }

    public Map<String, Object> getMergedBoundaryByParentDepartment(Integer tenantId, Integer parentDepartmentId, Integer parentLevel) {
        int childLevel = parentLevel + 1;
        String parentLevelColumn = getDeptLevelColumn(parentLevel);

        String sql = String.format("""
                SELECT
                    COUNT(*)::int AS child_count,
                    ST_AsGeoJSON(
                        ST_UnaryUnion(
                            ST_Collect(d.geom) FILTER (WHERE d.geom IS NOT NULL)
                        ),
                        9,
                        8
                    ) AS boundary_geojson
                FROM analytics_schema.dim_department_location_table d
                WHERE d.tenant_id = ?
                  AND d.department_level = ?
                  AND d.%1$s = ?
                """, parentLevelColumn);

        return jdbcTemplate.queryForMap(sql, tenantId, childLevel, parentDepartmentId);
    }

    public boolean tableExists(String schemaName, String tableName) {
        validateSchemaName(schemaName);
        validateTableName(tableName);
        String sql = "SELECT to_regclass(?) IS NOT NULL";
        String qualifiedTableName = schemaName + "." + tableName;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, qualifiedTableName);
        return Boolean.TRUE.equals(exists);
    }

    public boolean columnExists(String schemaName, String tableName, String columnName) {
        validateSchemaName(schemaName);
        validateTableName(tableName);
        validateTableName(columnName);
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = ?
                      AND column_name = ?
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schemaName, tableName, columnName);
        return Boolean.TRUE.equals(exists);
    }

    private String getDeptLevelColumn(int level) {
        if (level < 1 || level > 6) {
            throw new IllegalArgumentException("Unsupported department level: " + level);
        }
        return "level_" + level + "_dept_id";
    }

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    private void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid table/column name: " + tableName);
        }
    }
}
