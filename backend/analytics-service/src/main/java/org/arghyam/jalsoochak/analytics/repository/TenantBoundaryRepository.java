package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class TenantBoundaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> getMergedBoundaryForTenant(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
        // Analytics-source boundary: level-2 regions within tenant.
        String sql = """
                SELECT
                    COUNT(*)::int AS boundary_count,
                    ST_AsGeoJSON(
                        ST_UnaryUnion(
                            ST_Collect(l.geom)
                        ),
                        9,
                        8
                    ) AS boundary_geojson
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.tenant_id = ?
                  AND l.lgd_level = 2
                  AND l.geom IS NOT NULL
                """;
        return jdbcTemplate.queryForMap(sql, tenantId);
    }

    public Integer getLocationLevel(Integer lgdId) {
        if (lgdId == null || lgdId <= 0) {
            throw new IllegalArgumentException("lgd_id must be a positive integer");
        }
        String sql = """
                SELECT l.lgd_level
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.lgd_id = ?
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> (Integer) rs.getObject("lgd_level"), lgdId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String getBoundaryGeoJsonByLgdId(Integer tenantId, Integer lgdId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
        if (lgdId == null || lgdId <= 0) {
            throw new IllegalArgumentException("lgd_id must be a positive integer");
        }
        String sql = """
                SELECT ST_AsGeoJSON(l.geom, 9, 8) AS boundary_geojson
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.tenant_id = ?
                  AND l.lgd_id = ?
                  AND l.geom IS NOT NULL
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getString("boundary_geojson"), tenantId, lgdId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> getChildLevelByParent(
            Integer tenantId,
            Integer parentLgdId,
            Integer parentLevel
    ) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
        if (parentLgdId == null || parentLgdId <= 0) {
            throw new IllegalArgumentException("parent_lgd_id must be a positive integer");
        }
        if (parentLevel == null || parentLevel <= 0) {
            throw new IllegalArgumentException("parent_lgd_level must be a positive integer");
        }
        if (parentLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int childLevel = parentLevel + 1;
        String parentColumn = resolveDimLgdParentColumn(parentLevel);
        String schemeScopeColumn = resolveSchemeLgdColumn(childLevel);

        String sql = String.format("""
                SELECT
                    l.lgd_id AS lgd_id,
                    ?::int AS parent_lgd_id,
                    l.lgd_level AS child_level,
                    (
                        SELECT COUNT(*)::int
                        FROM analytics_schema.dim_scheme_table s
                        WHERE s.tenant_id = ?
                          AND s.%2$s = l.lgd_id
                    ) AS scheme_count,
                    l.title,
                    l.lgd_code,
                    ST_AsGeoJSON(l.geom, 9, 8) AS boundary_geojson
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.tenant_id = ?
                  AND l.lgd_level = ?
                  AND l.%1$s = ?
                ORDER BY l.lgd_id
                """, parentColumn, schemeScopeColumn);

        return jdbcTemplate.queryForList(sql, parentLgdId, tenantId, tenantId, childLevel, parentLgdId);
    }

    public Map<String, Object> getMergedBoundaryByParent(
            Integer tenantId,
            Integer parentLgdId,
            Integer parentLevel
    ) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
        if (parentLgdId == null || parentLgdId <= 0) {
            throw new IllegalArgumentException("parent_lgd_id must be a positive integer");
        }
        if (parentLevel == null || parentLevel <= 0) {
            throw new IllegalArgumentException("parent_lgd_level must be a positive integer");
        }
        if (parentLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int childLevel = parentLevel + 1;
        String parentColumn = resolveDimLgdParentColumn(parentLevel);
        String sql = String.format("""
                SELECT
                    COUNT(*)::int AS child_count,
                    ST_AsGeoJSON(
                        ST_UnaryUnion(
                            ST_Collect(l.geom) FILTER (WHERE l.geom IS NOT NULL)
                        ),
                        9,
                        8
                    ) AS boundary_geojson
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.tenant_id = ?
                  AND l.lgd_level = ?
                  AND l.%1$s = ?
                """, parentColumn);
        return jdbcTemplate.queryForMap(sql, tenantId, childLevel, parentLgdId);
    }

    private String resolveDimLgdParentColumn(int parentLevel) {
        // For child selection within dim_lgd_location_table:
        // children at level (parentLevel + 1) share the same level_{parentLevel}_lgd_id.
        return "level_" + parentLevel + "_lgd_id";
    }

    private String resolveSchemeLgdColumn(int lgdLevel) {
        if (lgdLevel < 1 || lgdLevel > 6) {
            throw new IllegalArgumentException("Invalid LGD level: " + lgdLevel);
        }
        return "level_" + lgdLevel + "_lgd_id";
    }
}
