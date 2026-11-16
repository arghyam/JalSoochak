package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Phased-rollout reads. A region is onboarded when a {@code dim_scheme_table} row of its tenant has the
 * region at its level and a {@code level_2_lgd_id} district whose {@code is_onboarded} is set (V62).
 */
@Repository
@RequiredArgsConstructor
public class RegionOnboardingRepository {

    /** The deepest level of either hierarchy; a region at this level has no children. */
    public static final int MAX_LEVEL = 6;

    private static final String FIND_LGD_LEVEL_SQL = """
            SELECT lgd_level AS region_level
            FROM analytics_schema.dim_lgd_location_table
            WHERE tenant_id = ?
              AND lgd_id = ?
            LIMIT 1
            """;

    private static final String FIND_DEPARTMENT_LEVEL_SQL = """
            SELECT department_level AS region_level
            FROM analytics_schema.dim_department_location_table
            WHERE tenant_id = ?
              AND department_id = ?
            LIMIT 1
            """;

    /** Keeps only the schemes whose district is onboarded. */
    private static final String ONBOARDED_DISTRICT_JOIN = """
            JOIN analytics_schema.dim_lgd_location_table d
              ON d.tenant_id = s.tenant_id
             AND d.lgd_id = s.level_2_lgd_id
             AND d.lgd_level = 2
             AND d.is_onboarded""";

    private static final String HAS_SCHEME_IN_ONBOARDED_DISTRICT_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM analytics_schema.dim_scheme_table s
                %1$s
                WHERE s.tenant_id = ?
                  AND s.%2$s = ?
            )
            """;

    private static final String FIND_ONBOARDED_CHILD_IDS_SQL = """
            SELECT DISTINCT s.%3$s AS child_id
            FROM analytics_schema.dim_scheme_table s
            %1$s
            WHERE s.tenant_id = ?
              AND s.%2$s = ?
              AND s.%3$s > 0
            ORDER BY child_id
            """;

    private final JdbcTemplate jdbcTemplate;

    /** The region's level in its dimension table, or empty when the tenant has no such region. */
    public Optional<Integer> findLevel(int tenantId, RegionHierarchy hierarchy, int regionId) {
        String sql = switch (hierarchy) {
            case LGD -> FIND_LGD_LEVEL_SQL;
            case DEPARTMENT -> FIND_DEPARTMENT_LEVEL_SQL;
        };
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getInt("region_level"), tenantId, regionId)
                .stream()
                .findFirst();
    }

    /** Whether at least one of the tenant's schemes sits in the region and in an onboarded district. */
    public boolean hasSchemeInOnboardedDistrict(int tenantId, RegionHierarchy hierarchy, int level, int regionId) {
        String sql = HAS_SCHEME_IN_ONBOARDED_DISTRICT_SQL.formatted(
                ONBOARDED_DISTRICT_JOIN, schemeColumn(hierarchy, level));
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, tenantId, regionId));
    }

    /**
     * The parent's children, one level down, that hold at least one scheme in an onboarded district,
     * in ascending id order. Empty for a parent at {@link #MAX_LEVEL}.
     */
    public List<Integer> findOnboardedChildIds(
            int tenantId, RegionHierarchy hierarchy, int parentLevel, int parentId) {
        if (parentLevel >= MAX_LEVEL) {
            return List.of();
        }
        String sql = FIND_ONBOARDED_CHILD_IDS_SQL.formatted(
                ONBOARDED_DISTRICT_JOIN,
                schemeColumn(hierarchy, parentLevel),
                schemeColumn(hierarchy, parentLevel + 1));
        return jdbcTemplate.queryForList(sql, Integer.class, tenantId, parentId);
    }

    /**
     * The {@code dim_scheme_table} column holding a scheme's region at {@code level}. Built only from the
     * enum and a range-checked int, never from request text, so formatting it into SQL is safe.
     */
    private static String schemeColumn(RegionHierarchy hierarchy, int level) {
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Unsupported " + hierarchy + " level: " + level);
        }
        return switch (hierarchy) {
            case LGD -> "level_" + level + "_lgd_id";
            case DEPARTMENT -> "level_" + level + "_dept_id";
        };
    }
}
