package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Repository
@RequiredArgsConstructor
public class SchemeRegularityRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final int NOT_SUBMITTED_STATUS = SubmissionStatus.NOT_SUBMITTED.getCode();
    private static final int EXPORT_FETCH_SIZE = 1_000;

    private String resolveDashboardSortDirection(String sortDir) {
        if (sortDir == null || sortDir.isBlank()) {
            return "DESC";
        }
        return switch (sortDir.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new IllegalArgumentException("Unsupported sort_dir: " + sortDir);
        };
    }

    private String resolveDashboardOrderBy(String sortBy, String sortDir, boolean lgdScope, long daysInRange) {
        String direction = resolveDashboardSortDirection(sortDir);
        String normalizedSort = sortBy == null ? "reportingrate" : sortBy.replace("_", "").trim().toLowerCase(Locale.ROOT);
        String expression = switch (normalizedSort) {
            case "schemename" -> "LOWER(COALESCE(ss.scheme_name, ''))";
            case "lgd", "location" -> lgdScope
                    ? "LOWER(COALESCE(NULLIF(pl.title, ''), pl.lgd_c_name, ''))"
                    : "LOWER(COALESCE(NULLIF(pd.title, ''), pd.department_c_name, ''))";
            case "reportingrate" -> "(COALESCE(sd.submission_days, 0)::numeric / " + daysInRange + ")";
            case "totalwatersupplied" -> "COALESCE(sd.total_water_supplied, 0)";
            default -> throw new IllegalArgumentException("Unsupported sort_by: " + sortBy);
        };
        return expression + " " + direction + ", ss.scheme_id ASC";
    }

    private SchemeSubmissionMetrics mapSchemeSubmissionMetrics(ResultSet rs) throws SQLException {
        return new SchemeSubmissionMetrics(
                rs.getInt("scheme_id"),
                rs.getString("scheme_name"),
                (Integer) rs.getObject("operating_status"),
                rs.getInt("submission_days"),
                rs.getLong("total_water_supplied"),
                (Integer) rs.getObject("immediate_parent_lgd_id"),
                rs.getString("immediate_parent_lgd_c_name"),
                rs.getString("immediate_parent_lgd_title"),
                (Integer) rs.getObject("immediate_parent_lgd_level"),
                (Integer) rs.getObject("immediate_parent_department_id"),
                rs.getString("immediate_parent_department_c_name"),
                rs.getString("immediate_parent_department_title"),
                (Integer) rs.getObject("immediate_parent_department_level"),
                (Integer) rs.getObject("level_1_lgd_id"),
                (Integer) rs.getObject("level_2_lgd_id"),
                (Integer) rs.getObject("level_3_lgd_id"),
                (Integer) rs.getObject("level_4_lgd_id"),
                (Integer) rs.getObject("level_5_lgd_id"),
                (Integer) rs.getObject("level_6_lgd_id"),
                (Integer) rs.getObject("level_1_dept_id"),
                (Integer) rs.getObject("level_2_dept_id"),
                (Integer) rs.getObject("level_3_dept_id"),
                (Integer) rs.getObject("level_4_dept_id"),
                (Integer) rs.getObject("level_5_dept_id"),
                (Integer) rs.getObject("level_6_dept_id"),
                getIntegerList(rs, "supplied_lgd_location_ids"),
                getStringList(rs, "supplied_lgd_location_c_names"),
                getStringList(rs, "supplied_lgd_location_titles"),
                getIntegerList(rs, "supplied_lgd_location_levels"));
    }

    private List<Integer> getIntegerList(ResultSet rs, String columnName) throws SQLException {
        java.sql.Array array = rs.getArray(columnName);
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof Integer[] integers) {
            return Arrays.asList(integers);
        }
        if (value instanceof Object[] objects) {
            return Arrays.stream(objects)
                    .map(item -> item instanceof Number number ? number.intValue() : null)
                    .toList();
        }
        return List.of();
    }

    private List<String> getStringList(ResultSet rs, String columnName) throws SQLException {
        java.sql.Array array = rs.getArray(columnName);
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        if (value instanceof String[] strings) {
            return Arrays.asList(strings);
        }
        if (value instanceof Object[] objects) {
            return Arrays.stream(objects)
                    .map(item -> item == null ? null : item.toString())
                    .toList();
        }
        return List.of();
    }

    public SchemeRegularityMetrics getSchemeRegularityMetrics(Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint AS day_water_quantity,
                        MAX(CASE WHEN m.confirmed_reading > 0 THEN 1 ELSE 0 END)::int AS has_supply
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_lgd sl
                        ON sl.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_supply_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_supply = 1)::int AS supply_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_lgd) AS scheme_count,
                    COALESCE((SELECT SUM(supply_days)::int FROM scheme_supply_days), 0) AS total_supply_days
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, parentLgdId, startDate, endDate);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getSchemeRegularityMetrics(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint AS day_water_quantity,
                        MAX(CASE WHEN m.confirmed_reading > 0 THEN 1 ELSE 0 END)::int AS has_supply
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_lgd sl
                        ON sl.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_supply_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_supply = 1)::int AS supply_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_lgd) AS scheme_count,
                    COALESCE((SELECT SUM(supply_days)::int FROM scheme_supply_days), 0) AS total_supply_days
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, parentLgdId, tenantId, startDate, endDate, tenantId);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getReadingSubmissionRateMetricsByLgd(Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        MAX(CASE WHEN m.confirmed_reading >= 0 THEN 1 ELSE 0 END)::int AS has_submission
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_lgd sl
                        ON sl.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_submission_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_submission = 1)::int AS submission_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_lgd) AS scheme_count,
                    COALESCE((SELECT SUM(submission_days)::int FROM scheme_submission_days), 0) AS total_supply_days
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, parentLgdId, startDate, endDate);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getReadingSubmissionRateMetricsByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        MAX(CASE WHEN m.confirmed_reading >= 0 THEN 1 ELSE 0 END)::int AS has_submission
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_lgd sl
                        ON sl.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_submission_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_submission = 1)::int AS submission_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_lgd) AS scheme_count,
                    COALESCE((SELECT SUM(submission_days)::int FROM scheme_submission_days), 0) AS total_supply_days
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, parentLgdId, tenantId, startDate, endDate, tenantId);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getSchemeRegularityMetricsByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint AS day_water_quantity,
                        MAX(CASE WHEN m.confirmed_reading > 0 THEN 1 ELSE 0 END)::int AS has_supply
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_department sd
                        ON sd.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_supply_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_supply = 1)::int AS supply_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_department) AS scheme_count,
                    COALESCE((SELECT SUM(supply_days)::int FROM scheme_supply_days), 0) AS total_supply_days
                """, schemeDepartmentColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, parentDepartmentId, startDate, endDate);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getSchemeRegularityMetricsByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint AS day_water_quantity,
                        MAX(CASE WHEN m.confirmed_reading > 0 THEN 1 ELSE 0 END)::int AS has_supply
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_department sd
                        ON sd.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_supply_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_supply = 1)::int AS supply_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_department) AS scheme_count,
                    COALESCE((SELECT SUM(supply_days)::int FROM scheme_supply_days), 0) AS total_supply_days
                """, schemeDepartmentColumn);

        Map<String, Object> result =
                jdbcTemplate.queryForMap(sql, parentDepartmentId, tenantId, startDate, endDate, tenantId);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getReadingSubmissionRateMetricsByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        MAX(CASE WHEN m.confirmed_reading >= 0 THEN 1 ELSE 0 END)::int AS has_submission
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_department sd
                        ON sd.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_submission_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_submission = 1)::int AS submission_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_department) AS scheme_count,
                    COALESCE((SELECT SUM(submission_days)::int FROM scheme_submission_days), 0) AS total_supply_days
                """, schemeDepartmentColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, parentDepartmentId, startDate, endDate);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public SchemeRegularityMetrics getReadingSubmissionRateMetricsByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        MAX(CASE WHEN m.confirmed_reading >= 0 THEN 1 ELSE 0 END)::int AS has_submission
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_department sd
                        ON sd.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_submission_days AS (
                    SELECT
                        sd.scheme_id,
                        COUNT(*) FILTER (WHERE sd.has_submission = 1)::int AS submission_days
                    FROM scheme_day sd
                    GROUP BY sd.scheme_id
                )
                SELECT
                    (SELECT COUNT(*)::int FROM schemes_in_department) AS scheme_count,
                    COALESCE((SELECT SUM(submission_days)::int FROM scheme_submission_days), 0) AS total_supply_days
                """, schemeDepartmentColumn);

        Map<String, Object> result =
                jdbcTemplate.queryForMap(sql, parentDepartmentId, tenantId, startDate, endDate, tenantId);
        int schemeCount = result.get("scheme_count") instanceof Number value ? value.intValue() : 0;
        int totalSupplyDays = result.get("total_supply_days") instanceof Number value ? value.intValue() : 0;

        return new SchemeRegularityMetrics(schemeCount, totalSupplyDays);
    }

    public BigDecimal getAveragePerformanceScoreByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        return getAveragePerformanceScoreByScopeColumn(schemeLgdColumn, parentLgdId, startDate, endDate);
    }

    public BigDecimal getAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        return getAveragePerformanceScoreByScopeColumn(
                schemeDepartmentColumn, parentDepartmentId, startDate, endDate);
    }

    private BigDecimal getAveragePerformanceScoreByScopeColumn(
            String schemeScopeColumn, Integer scopeId, LocalDate startDate, LocalDate endDate) {
        // Build a scope-specific scheme list first so aggregation stays limited to the selected boundary.
        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    COALESCE(AVG(fp.performance_score), 0)::numeric AS average_performance_score
                FROM analytics_schema.fact_scheme_performance_table fp
                JOIN schemes_in_scope ss
                    ON ss.scheme_id = fp.scheme_id
                WHERE fp.last_water_supply_date BETWEEN ? AND ?
                """, schemeScopeColumn);

        // Average across all scheme-day records in the requested period (0 when no rows exist).
        BigDecimal averagePerformanceScore = jdbcTemplate.queryForObject(
                sql, BigDecimal.class, scopeId, startDate, endDate);
        return averagePerformanceScore == null ? BigDecimal.ZERO : averagePerformanceScore;
    }

    public List<ChildRegionPerformanceScore> getChildAveragePerformanceScoreByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (lgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int childLevel = lgdLevel + 1;
        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    COALESCE(AVG(fp.performance_score), 0)::numeric AS average_performance_score
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_lgd_id = c.child_lgd_id
                LEFT JOIN analytics_schema.fact_scheme_performance_table fp
                    ON fp.scheme_id = s.scheme_id
                    AND fp.last_water_supply_date BETWEEN ? AND ?
                GROUP BY c.child_lgd_id
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionPerformanceScore(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getBigDecimal("average_performance_score")),
                childLevel,
                parentLgdId,
                parentLgdId,
                startDate,
                endDate);
    }

    public List<ChildRegionPerformanceScore> getChildAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (departmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int childLevel = departmentLevel + 1;
        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        d.department_id AS child_department_id
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                )
                SELECT
                    c.child_department_id AS department_id,
                    COALESCE(AVG(fp.performance_score), 0)::numeric AS average_performance_score
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_department_id = c.child_department_id
                LEFT JOIN analytics_schema.fact_scheme_performance_table fp
                    ON fp.scheme_id = s.scheme_id
                    AND fp.last_water_supply_date BETWEEN ? AND ?
                GROUP BY c.child_department_id
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionPerformanceScore(
                        null,
                        rs.getInt("department_id"),
                        rs.getBigDecimal("average_performance_score")),
                childLevel,
                parentDepartmentId,
                parentDepartmentId,
                startDate,
                endDate);
    }

    public List<ChildRegionReadingSubmissionMetrics> getChildReadingSubmissionRateMetricsByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (lgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }
        int childLevel = lgdLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                ),
                scheme_submission_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.submission_days), 0)::int AS total_submission_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_lgd_id = c.child_lgd_id
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_lgd_id, c.title
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSubmissionDays = rs.getInt("total_submission_days");
                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionReadingSubmissionMetrics(
                            rs.getInt("lgd_id"),
                            null,
                            rs.getString("title"),
                            schemeCount,
                            totalSubmissionDays,
                            readingSubmissionRate);
                },
                childLevel,
                parentLgdId,
                parentLgdId,
                startDate,
                endDate);
    }

    public List<ChildRegionReadingSubmissionMetrics> getChildReadingSubmissionRateMetricsByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (lgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }
        int childLevel = lgdLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                      AND l.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_submission_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.submission_days), 0)::int AS total_submission_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_lgd_id = c.child_lgd_id
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_lgd_id, c.title
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSubmissionDays = rs.getInt("total_submission_days");
                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionReadingSubmissionMetrics(
                            rs.getInt("lgd_id"),
                            null,
                            rs.getString("title"),
                            schemeCount,
                            totalSubmissionDays,
                            readingSubmissionRate);
                },
                childLevel,
                parentLgdId,
                tenantId,
                parentLgdId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionReadingSubmissionMetrics> getChildReadingSubmissionRateMetricsByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (departmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }
        int childLevel = departmentLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                ),
                scheme_submission_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.submission_days), 0)::int AS total_submission_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_department_id = c.child_department_id
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_department_id, c.title
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSubmissionDays = rs.getInt("total_submission_days");
                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionReadingSubmissionMetrics(
                            null,
                            rs.getInt("department_id"),
                            rs.getString("title"),
                            schemeCount,
                            totalSubmissionDays,
                            readingSubmissionRate);
                },
                childLevel,
                parentDepartmentId,
                parentDepartmentId,
                startDate,
                endDate);
    }

    public List<ChildRegionReadingSubmissionMetrics> getChildReadingSubmissionRateMetricsByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (departmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }
        int childLevel = departmentLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                      AND d.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_submission_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.submission_days), 0)::int AS total_submission_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_department_id = c.child_department_id
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_department_id, c.title
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSubmissionDays = rs.getInt("total_submission_days");
                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionReadingSubmissionMetrics(
                            null,
                            rs.getInt("department_id"),
                            rs.getString("title"),
                            schemeCount,
                            totalSubmissionDays,
                            readingSubmissionRate);
                },
                childLevel,
                parentDepartmentId,
                tenantId,
                parentDepartmentId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionSchemeRegularityMetrics> getChildSchemeRegularityMetricsByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (lgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }
        int childLevel = lgdLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                ),
                scheme_supply_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.supply_days), 0)::int AS total_supply_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_lgd_id = c.child_lgd_id
                LEFT JOIN scheme_supply_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_lgd_id, c.title
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSupplyDays = rs.getInt("total_supply_days");
                    BigDecimal averageRegularity = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        averageRegularity = BigDecimal.valueOf(totalSupplyDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionSchemeRegularityMetrics(
                            rs.getInt("lgd_id"),
                            null,
                            rs.getString("title"),
                            schemeCount,
                            totalSupplyDays,
                            averageRegularity);
                },
                childLevel,
                parentLgdId,
                parentLgdId,
                startDate,
                endDate);
    }

    public List<ChildRegionSchemeRegularityMetrics> getChildSchemeRegularityMetricsByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (lgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }
        int childLevel = lgdLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                      AND l.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_supply_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.supply_days), 0)::int AS total_supply_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_lgd_id = c.child_lgd_id
                LEFT JOIN scheme_supply_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_lgd_id, c.title
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSupplyDays = rs.getInt("total_supply_days");
                    BigDecimal averageRegularity = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        averageRegularity = BigDecimal.valueOf(totalSupplyDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionSchemeRegularityMetrics(
                            rs.getInt("lgd_id"),
                            null,
                            rs.getString("title"),
                            schemeCount,
                            totalSupplyDays,
                            averageRegularity);
                },
                childLevel,
                parentLgdId,
                tenantId,
                parentLgdId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionSchemeRegularityMetrics> getChildSchemeRegularityMetricsByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (departmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }
        int childLevel = departmentLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                ),
                scheme_supply_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.supply_days), 0)::int AS total_supply_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_department_id = c.child_department_id
                LEFT JOIN scheme_supply_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_department_id, c.title
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSupplyDays = rs.getInt("total_supply_days");
                    BigDecimal averageRegularity = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        averageRegularity = BigDecimal.valueOf(totalSupplyDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionSchemeRegularityMetrics(
                            null,
                            rs.getInt("department_id"),
                            rs.getString("title"),
                            schemeCount,
                            totalSupplyDays,
                            averageRegularity);
                },
                childLevel,
                parentDepartmentId,
                parentDepartmentId,
                startDate,
                endDate);
    }

    public List<ChildRegionSchemeRegularityMetrics> getChildSchemeRegularityMetricsByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (departmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }
        int childLevel = departmentLevel + 1;
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                      AND d.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_supply_days AS (
                    SELECT m.scheme_id, COUNT(DISTINCT m.reading_date)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.supply_days), 0)::int AS total_supply_days
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_department_id = c.child_department_id
                LEFT JOIN scheme_supply_days sd
                    ON sd.scheme_id = s.scheme_id
                GROUP BY c.child_department_id, c.title
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    int schemeCount = rs.getInt("scheme_count");
                    int totalSupplyDays = rs.getInt("total_supply_days");
                    BigDecimal averageRegularity = BigDecimal.ZERO;
                    if (schemeCount > 0) {
                        averageRegularity = BigDecimal.valueOf(totalSupplyDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return new ChildRegionSchemeRegularityMetrics(
                            null,
                            rs.getInt("department_id"),
                            rs.getString("title"),
                            schemeCount,
                            totalSupplyDays,
                            averageRegularity);
                },
                childLevel,
                parentDepartmentId,
                tenantId,
                parentDepartmentId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<OutageReasonSchemeCount> getOutageReasonSchemeCountByLgd(
            Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_lgd sl
                    ON sl.scheme_id = f.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.outage_reason
                ORDER BY f.outage_reason
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OutageReasonSchemeCount(
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                startDate,
                endDate);
    }

    public List<OutageReasonSchemeCount> getOutageReasonSchemeCountByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_lgd sl
                    ON sl.scheme_id = f.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY f.outage_reason
                ORDER BY f.outage_reason
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OutageReasonSchemeCount(
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<OutageReasonSchemeCount> getOutageReasonSchemeCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_department sd
                    ON sd.scheme_id = f.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.outage_reason
                ORDER BY f.outage_reason
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OutageReasonSchemeCount(
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                startDate,
                endDate);
    }

    public List<OutageReasonSchemeCount> getOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_department sd
                    ON sd.scheme_id = f.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY f.outage_reason
                ORDER BY f.outage_reason
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OutageReasonSchemeCount(
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<OutageReasonSchemeCount> getOutageReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us
                    ON us.scheme_id = f.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.tenant_id = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.outage_reason
                ORDER BY f.outage_reason
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OutageReasonSchemeCount(
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                userId,
                tenantId,
                tenantId,
                startDate,
                endDate);
    }

    public List<DailyOutageReasonSchemeCount> getDailyOutageReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.date,
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us
                    ON us.scheme_id = f.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.tenant_id = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.date, f.outage_reason
                ORDER BY f.date, f.outage_reason
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DailyOutageReasonSchemeCount(
                        rs.getObject("date", LocalDate.class),
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                userId,
                tenantId,
                tenantId,
                startDate,
                endDate);
    }

    public List<NonSubmissionReasonSchemeCount> getNonSubmissionReasonSchemeCountByLgd(
            Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_lgd sl
                    ON sl.scheme_id = f.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.non_submission_reason
                ORDER BY f.non_submission_reason
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NonSubmissionReasonSchemeCount(
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate);
    }

    public List<NonSubmissionReasonSchemeCount> getNonSubmissionReasonSchemeCountByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_lgd AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_lgd sl
                    ON sl.scheme_id = f.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY f.non_submission_reason
                ORDER BY f.non_submission_reason
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NonSubmissionReasonSchemeCount(
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                tenantId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate,
                tenantId);
    }

    public List<NonSubmissionReasonSchemeCount> getNonSubmissionReasonSchemeCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_department sd
                    ON sd.scheme_id = f.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.non_submission_reason
                ORDER BY f.non_submission_reason
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NonSubmissionReasonSchemeCount(
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate);
    }

    public List<NonSubmissionReasonSchemeCount> getNonSubmissionReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_department AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN schemes_in_department sd
                    ON sd.scheme_id = f.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY f.non_submission_reason
                ORDER BY f.non_submission_reason
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NonSubmissionReasonSchemeCount(
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                tenantId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate,
                tenantId);
    }

    public List<NonSubmissionReasonSchemeCount> getNonSubmissionReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us
                    ON us.scheme_id = f.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.tenant_id = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.non_submission_reason
                ORDER BY f.non_submission_reason
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NonSubmissionReasonSchemeCount(
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                userId,
                tenantId,
                NOT_SUBMITTED_STATUS,
                tenantId,
                startDate,
                endDate);
    }

    public List<DailyNonSubmissionReasonSchemeCount> getDailyNonSubmissionReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    f.date,
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us
                    ON us.scheme_id = f.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.tenant_id = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.date, f.non_submission_reason
                ORDER BY f.date, f.non_submission_reason
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DailyNonSubmissionReasonSchemeCount(
                        rs.getObject("date", LocalDate.class),
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                userId,
                tenantId,
                NOT_SUBMITTED_STATUS,
                tenantId,
                startDate,
                endDate);
    }

    public Integer getSchemeCountByUser(Integer tenantId, Integer userId) {
        String sql = """
                SELECT COALESCE(COUNT(DISTINCT usm.scheme_id), 0)::int AS scheme_count
                FROM analytics_schema.dim_user_scheme_mapping_table usm
                WHERE usm.user_id = ?
                  AND usm.tenant_id = ?
                """;

        return jdbcTemplate.queryForObject(sql, Integer.class, userId, tenantId);
    }

    public long getTotalWaterSuppliedByUserSchemes(Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                )
                SELECT COALESCE(SUM(f.water_quantity), 0)::bigint AS total_water_supplied
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us
                    ON us.scheme_id = f.scheme_id
                WHERE f.tenant_id = ?
                  AND f.date BETWEEN ? AND ?
                """;

        Long value = jdbcTemplate.queryForObject(sql, Long.class, userId, tenantId, tenantId, startDate, endDate);
        return value != null ? value : 0L;
    }

    public SubmissionStatusCount getSubmissionStatusCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                )
                SELECT
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading = m.confirmed_reading
                        ),
                        0
                    )::int AS compliant_submission_count,
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading IS DISTINCT FROM m.confirmed_reading
                        ),
                        0
                    )::int AS anomalous_submission_count
                FROM analytics_schema.fact_meter_reading_table m
                JOIN user_schemes us
                    ON us.scheme_id = m.scheme_id
                WHERE m.tenant_id = ?
                  AND m.reading_date BETWEEN ? AND ?
                """;

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, userId, tenantId, tenantId, startDate, endDate);
        int compliantSubmissionCount =
                result.get("compliant_submission_count") instanceof Number value ? value.intValue() : 0;
        int anomalousSubmissionCount =
                result.get("anomalous_submission_count") instanceof Number value ? value.intValue() : 0;
        return new SubmissionStatusCount(compliantSubmissionCount, anomalousSubmissionCount);
    }

    public Integer getSchemeCountByLgd(Integer lgdId) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                """, schemeLgdColumn);

        return jdbcTemplate.queryForObject(sql, Integer.class, lgdId);
    }

    public Integer getSchemeCountByLgd(Integer tenantId, Integer lgdId) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                """, schemeLgdColumn);

        return jdbcTemplate.queryForObject(sql, Integer.class, lgdId, tenantId);
    }

    public Integer getSchemeCountByDepartment(Integer departmentId) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.queryForObject(sql, Integer.class, departmentId);
    }

    public Integer getSchemeCountByDepartment(Integer tenantId, Integer departmentId) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.queryForObject(sql, Integer.class, departmentId, tenantId);
    }

    public SubmissionStatusCount getSubmissionStatusCountByLgd(
            Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading = m.confirmed_reading
                        ),
                        0
                    )::int AS compliant_submission_count,
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading IS DISTINCT FROM m.confirmed_reading
                        ),
                        0
                    )::int AS anomalous_submission_count
                FROM analytics_schema.fact_meter_reading_table m
                JOIN schemes_in_scope ss
                    ON ss.scheme_id = m.scheme_id
                WHERE m.reading_date BETWEEN ? AND ?
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, lgdId, startDate, endDate);
        int compliantSubmissionCount =
                result.get("compliant_submission_count") instanceof Number value ? value.intValue() : 0;
        int anomalousSubmissionCount =
                result.get("anomalous_submission_count") instanceof Number value ? value.intValue() : 0;
        return new SubmissionStatusCount(compliantSubmissionCount, anomalousSubmissionCount);
    }

    public SubmissionStatusCount getSubmissionStatusCountByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading = m.confirmed_reading
                        ),
                        0
                    )::int AS compliant_submission_count,
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading IS DISTINCT FROM m.confirmed_reading
                        ),
                        0
                    )::int AS anomalous_submission_count
                FROM analytics_schema.fact_meter_reading_table m
                JOIN schemes_in_scope ss
                    ON ss.scheme_id = m.scheme_id
                WHERE m.reading_date BETWEEN ? AND ?
                  AND m.tenant_id = ?
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, lgdId, tenantId, startDate, endDate, tenantId);
        int compliantSubmissionCount =
                result.get("compliant_submission_count") instanceof Number value ? value.intValue() : 0;
        int anomalousSubmissionCount =
                result.get("anomalous_submission_count") instanceof Number value ? value.intValue() : 0;
        return new SubmissionStatusCount(compliantSubmissionCount, anomalousSubmissionCount);
    }

    public SubmissionStatusCount getSubmissionStatusCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                )
                SELECT
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading = m.confirmed_reading
                        ),
                        0
                    )::int AS compliant_submission_count,
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading IS DISTINCT FROM m.confirmed_reading
                        ),
                        0
                    )::int AS anomalous_submission_count
                FROM analytics_schema.fact_meter_reading_table m
                JOIN schemes_in_scope ss
                    ON ss.scheme_id = m.scheme_id
                WHERE m.reading_date BETWEEN ? AND ?
                """, schemeDepartmentColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, departmentId, startDate, endDate);
        int compliantSubmissionCount =
                result.get("compliant_submission_count") instanceof Number value ? value.intValue() : 0;
        int anomalousSubmissionCount =
                result.get("anomalous_submission_count") instanceof Number value ? value.intValue() : 0;
        return new SubmissionStatusCount(compliantSubmissionCount, anomalousSubmissionCount);
    }

    public SubmissionStatusCount getSubmissionStatusCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading = m.confirmed_reading
                        ),
                        0
                    )::int AS compliant_submission_count,
                    COALESCE(
                        COUNT(*) FILTER (
                            WHERE m.extracted_reading IS NOT NULL
                              AND m.extracted_reading IS DISTINCT FROM m.confirmed_reading
                        ),
                        0
                    )::int AS anomalous_submission_count
                FROM analytics_schema.fact_meter_reading_table m
                JOIN schemes_in_scope ss
                    ON ss.scheme_id = m.scheme_id
                WHERE m.reading_date BETWEEN ? AND ?
                  AND m.tenant_id = ?
                """, schemeDepartmentColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, departmentId, tenantId, startDate, endDate, tenantId);
        int compliantSubmissionCount =
                result.get("compliant_submission_count") instanceof Number value ? value.intValue() : 0;
        int anomalousSubmissionCount =
                result.get("anomalous_submission_count") instanceof Number value ? value.intValue() : 0;
        return new SubmissionStatusCount(compliantSubmissionCount, anomalousSubmissionCount);
    }

    public List<DailySubmissionSchemeCount> getDailySubmissionSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    m.reading_date AS date,
                    COUNT(DISTINCT m.scheme_id)::int AS submitted_scheme_count
                FROM analytics_schema.fact_meter_reading_table m
                JOIN user_schemes us
                    ON us.scheme_id = m.scheme_id
                WHERE m.tenant_id = ?
                  AND m.extracted_reading IS NOT NULL
                  AND m.reading_date BETWEEN ? AND ?
                GROUP BY m.reading_date
                ORDER BY m.reading_date
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DailySubmissionSchemeCount(
                        rs.getObject("date", LocalDate.class),
                        rs.getInt("submitted_scheme_count")),
                userId,
                tenantId,
                tenantId,
                startDate,
                endDate);
    }

    public List<ChildRegionRef> getChildRegionsByLgd(Integer lgdId) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            return List.of();
        }

        int childLevel = lgdLevel + 1;
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                SELECT
                    l.lgd_id,
                    l.title
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.lgd_level = ?
                  AND l.%1$s = ?
                ORDER BY l.lgd_id
                """, childRegionParentLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionRef(rs.getInt("lgd_id"), null, rs.getString("title")),
                childLevel,
                lgdId);
    }

    public List<ChildRegionRef> getChildRegionsByLgd(Integer tenantId, Integer lgdId) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            return List.of();
        }

        int childLevel = lgdLevel + 1;
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                SELECT
                    l.lgd_id,
                    l.title
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.lgd_level = ?
                  AND l.%1$s = ?
                  AND l.tenant_id = ?
                ORDER BY l.lgd_id
                """, childRegionParentLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionRef(rs.getInt("lgd_id"), null, rs.getString("title")),
                childLevel,
                lgdId,
                tenantId);
    }

    public List<ChildRegionRef> getChildRegionsByDepartment(Integer departmentId) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        if (departmentLevel >= 6) {
            return List.of();
        }

        int childLevel = departmentLevel + 1;
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                SELECT
                    d.department_id,
                    d.title
                FROM analytics_schema.dim_department_location_table d
                WHERE d.department_level = ?
                  AND d.%1$s = ?
                ORDER BY d.department_id
                """, childRegionParentDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionRef(null, rs.getInt("department_id"), rs.getString("title")),
                childLevel,
                departmentId);
    }

    public List<ChildRegionRef> getChildRegionsByDepartment(Integer tenantId, Integer departmentId) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        if (departmentLevel >= 6) {
            return List.of();
        }

        int childLevel = departmentLevel + 1;
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                SELECT
                    d.department_id,
                    d.title
                FROM analytics_schema.dim_department_location_table d
                WHERE d.department_level = ?
                  AND d.%1$s = ?
                  AND d.tenant_id = ?
                ORDER BY d.department_id
                """, childRegionParentDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionRef(null, rs.getInt("department_id"), rs.getString("title")),
                childLevel,
                departmentId,
                tenantId);
    }

    public List<ChildRegionOutageReasonSchemeCount> getChildOutageReasonSchemeCountByLgd(
            Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                )
                SELECT
                    ss.child_lgd_id AS lgd_id,
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                GROUP BY ss.child_lgd_id, f.outage_reason
                ORDER BY ss.child_lgd_id, f.outage_reason
                """, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionOutageReasonSchemeCount(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                startDate,
                endDate);
    }

    public List<ChildRegionOutageReasonSchemeCount> getChildOutageReasonSchemeCountByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    ss.child_lgd_id AS lgd_id,
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY ss.child_lgd_id, f.outage_reason
                ORDER BY ss.child_lgd_id, f.outage_reason
                """, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionOutageReasonSchemeCount(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionOutageReasonSchemeCount> getChildOutageReasonSchemeCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        if (departmentLevel >= 6) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                )
                SELECT
                    ss.child_department_id AS department_id,
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                GROUP BY ss.child_department_id, f.outage_reason
                ORDER BY ss.child_department_id, f.outage_reason
                """, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionOutageReasonSchemeCount(
                        null,
                        rs.getInt("department_id"),
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                startDate,
                endDate);
    }

    public List<ChildRegionOutageReasonSchemeCount> getChildOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        if (departmentLevel >= 6) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    ss.child_department_id AS department_id,
                    f.outage_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY ss.child_department_id, f.outage_reason
                ORDER BY ss.child_department_id, f.outage_reason
                """, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionOutageReasonSchemeCount(
                        null,
                        rs.getInt("department_id"),
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionNonSubmissionReasonSchemeCount> getChildNonSubmissionReasonSchemeCountByLgd(
            Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                )
                SELECT
                    ss.child_lgd_id AS lgd_id,
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY ss.child_lgd_id, f.non_submission_reason
                ORDER BY ss.child_lgd_id, f.non_submission_reason
                """, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionNonSubmissionReasonSchemeCount(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate);
    }

    public List<ChildRegionNonSubmissionReasonSchemeCount> getChildNonSubmissionReasonSchemeCountByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            return List.of();
        }

        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    ss.child_lgd_id AS lgd_id,
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY ss.child_lgd_id, f.non_submission_reason
                ORDER BY ss.child_lgd_id, f.non_submission_reason
                """, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionNonSubmissionReasonSchemeCount(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                lgdId,
                tenantId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionNonSubmissionReasonSchemeCount> getChildNonSubmissionReasonSchemeCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        if (departmentLevel >= 6) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                )
                SELECT
                    ss.child_department_id AS department_id,
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                GROUP BY ss.child_department_id, f.non_submission_reason
                ORDER BY ss.child_department_id, f.non_submission_reason
                """, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionNonSubmissionReasonSchemeCount(
                        null,
                        rs.getInt("department_id"),
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate);
    }

    public List<ChildRegionNonSubmissionReasonSchemeCount> getChildNonSubmissionReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        if (departmentLevel >= 6) {
            return List.of();
        }

        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel + 1);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%1$s AS child_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%2$s = ?
                      AND s.tenant_id = ?
                )
                SELECT
                    ss.child_department_id AS department_id,
                    f.non_submission_reason,
                    COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                FROM schemes_in_scope ss
                JOIN analytics_schema.fact_water_quantity_table f
                    ON f.scheme_id = ss.scheme_id
                WHERE f.non_submission_reason IS NOT NULL
                  AND f.submission_status = ?
                  AND f.date BETWEEN ? AND ?
                  AND f.tenant_id = ?
                GROUP BY ss.child_department_id, f.non_submission_reason
                ORDER BY ss.child_department_id, f.non_submission_reason
                """, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionNonSubmissionReasonSchemeCount(
                        null,
                        rs.getInt("department_id"),
                        rs.getString("non_submission_reason"),
                        rs.getInt("scheme_count")),
                departmentId,
                tenantId,
                NOT_SUBMITTED_STATUS,
                startDate,
                endDate,
                tenantId);
    }

    public SchemeStatusCount getSchemeStatusCountByLgd(Integer lgdId) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status > 0)::int AS active_scheme_count,
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status = 0)::int AS inactive_scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, lgdId);
        int activeSchemeCount = result.get("active_scheme_count") instanceof Number value ? value.intValue() : 0;
        int inactiveSchemeCount = result.get("inactive_scheme_count") instanceof Number value ? value.intValue() : 0;

        return new SchemeStatusCount(activeSchemeCount, inactiveSchemeCount);
    }

    public SchemeStatusCount getSchemeStatusCountByLgd(Integer tenantId, Integer lgdId) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status > 0)::int AS active_scheme_count,
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status = 0)::int AS inactive_scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                """, schemeLgdColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, lgdId, tenantId);
        int activeSchemeCount = result.get("active_scheme_count") instanceof Number value ? value.intValue() : 0;
        int inactiveSchemeCount = result.get("inactive_scheme_count") instanceof Number value ? value.intValue() : 0;

        return new SchemeStatusCount(activeSchemeCount, inactiveSchemeCount);
    }

    public SchemeStatusCount getSchemeStatusCountByDepartment(Integer departmentId) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status > 0)::int AS active_scheme_count,
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status = 0)::int AS inactive_scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                """, schemeDepartmentColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, departmentId);
        int activeSchemeCount = result.get("active_scheme_count") instanceof Number value ? value.intValue() : 0;
        int inactiveSchemeCount = result.get("inactive_scheme_count") instanceof Number value ? value.intValue() : 0;

        return new SchemeStatusCount(activeSchemeCount, inactiveSchemeCount);
    }

    public SchemeStatusCount getSchemeStatusCountByDepartment(Integer tenantId, Integer departmentId) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status > 0)::int AS active_scheme_count,
                    COUNT(DISTINCT s.scheme_id) FILTER (WHERE s.operating_status = 0)::int AS inactive_scheme_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                """, schemeDepartmentColumn);

        Map<String, Object> result = jdbcTemplate.queryForMap(sql, departmentId, tenantId);
        int activeSchemeCount = result.get("active_scheme_count") instanceof Number value ? value.intValue() : 0;
        int inactiveSchemeCount = result.get("inactive_scheme_count") instanceof Number value ? value.intValue() : 0;

        return new SchemeStatusCount(activeSchemeCount, inactiveSchemeCount);
    }

    public long getCriticalSchemeCountByLgd(Integer tenantId, Integer lgdId, LocalDate cutoffDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                last_supply AS (
                    SELECT
                        m.scheme_id,
                        MAX(m.reading_date)::date AS last_supplied_date
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT COUNT(*)::bigint AS critical_count
                FROM schemes_in_scope ss
                LEFT JOIN last_supply ls
                  ON ls.scheme_id = ss.scheme_id
                WHERE ls.last_supplied_date IS NULL
                   OR ls.last_supplied_date < ?
                """, schemeLgdColumn);

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                lgdId,
                tenantId,
                tenantId,
                cutoffDate
        );
        return count == null ? 0L : count;
    }

    public long getCriticalSchemeCountByDepartment(Integer tenantId, Integer departmentId, LocalDate cutoffDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                last_supply AS (
                    SELECT
                        m.scheme_id,
                        MAX(m.reading_date)::date AS last_supplied_date
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT COUNT(*)::bigint AS critical_count
                FROM schemes_in_scope ss
                LEFT JOIN last_supply ls
                  ON ls.scheme_id = ss.scheme_id
                WHERE ls.last_supplied_date IS NULL
                   OR ls.last_supplied_date < ?
                """, schemeDepartmentColumn);

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                departmentId,
                tenantId,
                tenantId,
                cutoffDate
        );
        return count == null ? 0L : count;
    }

    public long getCriticalSchemeCountByUserSchemes(Integer tenantId, Integer userId, LocalDate cutoffDate) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                ),
                last_supply AS (
                    SELECT
                        m.scheme_id,
                        MAX(m.reading_date)::date AS last_supplied_date
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us
                      ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT COUNT(*)::bigint AS critical_count
                FROM user_schemes us
                LEFT JOIN last_supply ls
                  ON ls.scheme_id = us.scheme_id
                WHERE ls.last_supplied_date IS NULL
                   OR ls.last_supplied_date < ?
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                userId,
                tenantId,
                tenantId,
                cutoffDate
        );
        return count == null ? 0L : count;
    }

    public List<CriticalSchemeRow> getCriticalSchemesByUserSchemes(
            Integer tenantId,
            Integer userId,
            LocalDate cutoffDate,
            Integer limit,
            Integer offset
    ) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                ),
                scheme_details AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.state_scheme_id,
                        s.centre_scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    JOIN user_schemes us
                      ON us.scheme_id = s.scheme_id
                    WHERE s.tenant_id = ?
                ),
                last_supply AS (
                    SELECT
                        m.scheme_id,
                        MAX(m.reading_date)::date AS last_supplied_date
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us
                      ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    sd.scheme_id,
                    sd.scheme_name,
                    sd.state_scheme_id,
                    sd.centre_scheme_id,
                    ls.last_supplied_date
                FROM scheme_details sd
                LEFT JOIN last_supply ls
                  ON ls.scheme_id = sd.scheme_id
                WHERE ls.last_supplied_date IS NULL
                   OR ls.last_supplied_date < ?
                ORDER BY ls.last_supplied_date ASC NULLS FIRST, sd.scheme_id ASC
                LIMIT ?
                OFFSET ?
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new CriticalSchemeRow(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        rs.getDate("last_supplied_date") == null ? null : rs.getDate("last_supplied_date").toLocalDate()
                ),
                userId,
                tenantId,
                tenantId,
                tenantId,
                cutoffDate,
                limit,
                offset
        );
    }

    public List<CriticalSchemeRow> getCriticalSchemesByLgd(
            Integer tenantId,
            Integer lgdId,
            LocalDate cutoffDate,
            Integer limit,
            Integer offset
    ) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id, s.scheme_name, s.state_scheme_id, s.centre_scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                last_supply AS (
                    SELECT
                        m.scheme_id,
                        MAX(m.reading_date)::date AS last_supplied_date
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.state_scheme_id,
                    ss.centre_scheme_id,
                    ls.last_supplied_date
                FROM schemes_in_scope ss
                LEFT JOIN last_supply ls
                  ON ls.scheme_id = ss.scheme_id
                WHERE ls.last_supplied_date IS NULL
                   OR ls.last_supplied_date < ?
                ORDER BY ls.last_supplied_date ASC NULLS FIRST, ss.scheme_id ASC
                LIMIT ?
                OFFSET ?
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new CriticalSchemeRow(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        rs.getDate("last_supplied_date") == null ? null : rs.getDate("last_supplied_date").toLocalDate()
                ),
                lgdId,
                tenantId,
                tenantId,
                cutoffDate,
                limit,
                offset
        );
    }

    public List<CriticalSchemeRow> getCriticalSchemesByDepartment(
            Integer tenantId,
            Integer departmentId,
            LocalDate cutoffDate,
            Integer limit,
            Integer offset
    ) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id, s.scheme_name, s.state_scheme_id, s.centre_scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                last_supply AS (
                    SELECT
                        m.scheme_id,
                        MAX(m.reading_date)::date AS last_supplied_date
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.state_scheme_id,
                    ss.centre_scheme_id,
                    ls.last_supplied_date
                FROM schemes_in_scope ss
                LEFT JOIN last_supply ls
                  ON ls.scheme_id = ss.scheme_id
                WHERE ls.last_supplied_date IS NULL
                   OR ls.last_supplied_date < ?
                ORDER BY ls.last_supplied_date ASC NULLS FIRST, ss.scheme_id ASC
                LIMIT ?
                OFFSET ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new CriticalSchemeRow(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        rs.getDate("last_supplied_date") == null ? null : rs.getDate("last_supplied_date").toLocalDate()
                ),
                departmentId,
                tenantId,
                tenantId,
                cutoffDate,
                limit,
                offset
        );
    }

    public long getContinuousSchemeCountByLgd(
            Integer tenantId,
            Integer lgdId,
            LocalDate startDate,
            LocalDate endDate,
            int daysInRange
    ) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id, s.scheme_name
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                supply_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT COUNT(*)::bigint AS continuous_count
                FROM schemes_in_scope ss
                LEFT JOIN supply_days sd
                  ON sd.scheme_id = ss.scheme_id
                WHERE COALESCE(sd.supply_days, 0) = ?
                """, schemeLgdColumn);

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                lgdId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                daysInRange
        );
        return count == null ? 0L : count;
    }

    public long getContinuousSchemeCountByDepartment(
            Integer tenantId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            int daysInRange
    ) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id, s.scheme_name
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                supply_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT COUNT(*)::bigint AS continuous_count
                FROM schemes_in_scope ss
                LEFT JOIN supply_days sd
                  ON sd.scheme_id = ss.scheme_id
                WHERE COALESCE(sd.supply_days, 0) = ?
                """, schemeDepartmentColumn);

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                departmentId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                daysInRange
        );
        return count == null ? 0L : count;
    }

    public long getContinuousSchemeCountByUserSchemes(
            Integer tenantId,
            Integer userId,
            LocalDate startDate,
            LocalDate endDate,
            int daysInRange
    ) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                ),
                supply_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us
                      ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT COUNT(*)::bigint AS continuous_count
                FROM user_schemes us
                LEFT JOIN supply_days sd
                  ON sd.scheme_id = us.scheme_id
                WHERE COALESCE(sd.supply_days, 0) = ?
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                userId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                daysInRange
        );
        return count == null ? 0L : count;
    }

    public List<ContinuousSchemeRow> getContinuousSchemesByLgd(
            Integer tenantId,
            Integer lgdId,
            LocalDate startDate,
            LocalDate endDate,
            int daysInRange,
            Integer limit,
            Integer offset
    ) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id, s.scheme_name
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                supply_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name
                FROM schemes_in_scope ss
                LEFT JOIN supply_days sd
                  ON sd.scheme_id = ss.scheme_id
                WHERE COALESCE(sd.supply_days, 0) = ?
                ORDER BY ss.scheme_id ASC
                LIMIT ?
                OFFSET ?
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ContinuousSchemeRow(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name")
                ),
                lgdId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                daysInRange,
                limit,
                offset
        );
    }

    public List<ContinuousSchemeRow> getContinuousSchemesByUserSchemes(
            Integer tenantId,
            Integer userId,
            LocalDate startDate,
            LocalDate endDate,
            int daysInRange,
            Integer limit,
            Integer offset
    ) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id
                    WHERE usm.user_id = ?
                      AND s.tenant_id = ?
                ),
                scheme_details AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name
                    FROM analytics_schema.dim_scheme_table s
                    JOIN user_schemes us
                      ON us.scheme_id = s.scheme_id
                    WHERE s.tenant_id = ?
                ),
                supply_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us
                      ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    sd.scheme_id,
                    sd.scheme_name
                FROM scheme_details sd
                LEFT JOIN supply_days sdy
                  ON sdy.scheme_id = sd.scheme_id
                WHERE COALESCE(sdy.supply_days, 0) = ?
                ORDER BY sd.scheme_id ASC
                LIMIT ?
                OFFSET ?
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ContinuousSchemeRow(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name")
                ),
                userId,
                tenantId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                daysInRange,
                limit,
                offset
        );
    }

    public List<ContinuousSchemeRow> getContinuousSchemesByDepartment(
            Integer tenantId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            int daysInRange,
            Integer limit,
            Integer offset
    ) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id, s.scheme_name
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                supply_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                      ON ss.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name
                FROM schemes_in_scope ss
                LEFT JOIN supply_days sd
                  ON sd.scheme_id = ss.scheme_id
                WHERE COALESCE(sd.supply_days, 0) = ?
                ORDER BY ss.scheme_id ASC
                LIMIT ?
                OFFSET ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ContinuousSchemeRow(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name")
                ),
                departmentId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                daysInRange,
                limit,
                offset
        );
    }

    public long getSchemeCountByLgdInScope(Integer tenantId, Integer lgdId) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT COUNT(DISTINCT s.scheme_id)::bigint AS total_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                """, schemeLgdColumn);

        Long count = jdbcTemplate.queryForObject(sql, Long.class, lgdId, tenantId);
        return count == null ? 0L : count;
    }

    public long getSchemeCountByDepartmentInScope(Integer tenantId, Integer departmentId) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT COUNT(DISTINCT s.scheme_id)::bigint AS total_count
                FROM analytics_schema.dim_scheme_table s
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                """, schemeDepartmentColumn);

        Long count = jdbcTemplate.queryForObject(sql, Long.class, departmentId, tenantId);
        return count == null ? 0L : count;
    }

    public List<SchemeSubmissionMetrics> getTopSchemeSubmissionMetricsByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate, Integer topSchemeCount) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.operating_status,
                        s.level_1_lgd_id,
                        s.level_2_lgd_id,
                        s.level_3_lgd_id,
                        s.level_4_lgd_id,
                        s.level_5_lgd_id,
                        s.level_6_lgd_id,
                        s.level_1_dept_id,
                        s.level_2_dept_id,
                        s.level_3_dept_id,
                        s.level_4_dept_id,
                        s.level_5_dept_id,
                        s.level_6_dept_id,
                        CASE
                            WHEN s.level_6_lgd_id IS NOT NULL THEN s.level_5_lgd_id
                            WHEN s.level_5_lgd_id IS NOT NULL THEN s.level_4_lgd_id
                            WHEN s.level_4_lgd_id IS NOT NULL THEN s.level_3_lgd_id
                            WHEN s.level_3_lgd_id IS NOT NULL THEN s.level_2_lgd_id
                            WHEN s.level_2_lgd_id IS NOT NULL THEN s.level_1_lgd_id
                            WHEN s.level_1_lgd_id IS NOT NULL THEN s.parent_lgd_location_id
                            ELSE NULL
                        END AS immediate_parent_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_submission_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.operating_status AS operating_status,
                    COALESCE(sd.submission_days, 0)::int AS submission_days,
                    COALESCE(sd.total_water_supplied, 0)::bigint AS total_water_supplied,
                    ss.immediate_parent_lgd_id,
                    pl.lgd_c_name AS immediate_parent_lgd_c_name,
                    pl.title AS immediate_parent_lgd_title,
                    pl.lgd_level AS immediate_parent_lgd_level,
                    NULL::int AS immediate_parent_department_id,
                    NULL::varchar AS immediate_parent_department_c_name,
                    NULL::varchar AS immediate_parent_department_title,
                    NULL::int AS immediate_parent_department_level,
                    ss.level_1_lgd_id,
                    ss.level_2_lgd_id,
                    ss.level_3_lgd_id,
                    ss.level_4_lgd_id,
                    ss.level_5_lgd_id,
                    ss.level_6_lgd_id,
                    ss.level_1_dept_id,
                    ss.level_2_dept_id,
                    ss.level_3_dept_id,
                    ss.level_4_dept_id,
                    ss.level_5_dept_id,
                    ss.level_6_dept_id,
                    ARRAY[]::integer[] AS supplied_lgd_location_ids,
                    ARRAY[]::varchar[] AS supplied_lgd_location_c_names,
                    ARRAY[]::varchar[] AS supplied_lgd_location_titles,
                    ARRAY[]::integer[] AS supplied_lgd_location_levels
                FROM schemes_in_scope ss
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = ss.scheme_id
                LEFT JOIN analytics_schema.dim_lgd_location_table pl
                    ON pl.lgd_id = ss.immediate_parent_lgd_id
                ORDER BY
                    (COALESCE(sd.submission_days, 0)::numeric / ?) DESC,
                    ss.scheme_id ASC
                LIMIT ?
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeSubmissionMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("operating_status"),
                        rs.getInt("submission_days"),
                        rs.getLong("total_water_supplied"),
                        (Integer) rs.getObject("immediate_parent_lgd_id"),
                        rs.getString("immediate_parent_lgd_c_name"),
                        rs.getString("immediate_parent_lgd_title"),
                        (Integer) rs.getObject("immediate_parent_lgd_level"),
                        (Integer) rs.getObject("immediate_parent_department_id"),
                        rs.getString("immediate_parent_department_c_name"),
                        rs.getString("immediate_parent_department_title"),
                        (Integer) rs.getObject("immediate_parent_department_level"),
                        (Integer) rs.getObject("level_1_lgd_id"),
                        (Integer) rs.getObject("level_2_lgd_id"),
                        (Integer) rs.getObject("level_3_lgd_id"),
                        (Integer) rs.getObject("level_4_lgd_id"),
                        (Integer) rs.getObject("level_5_lgd_id"),
                        (Integer) rs.getObject("level_6_lgd_id"),
                        (Integer) rs.getObject("level_1_dept_id"),
                        (Integer) rs.getObject("level_2_dept_id"),
                        (Integer) rs.getObject("level_3_dept_id"),
                        (Integer) rs.getObject("level_4_dept_id"),
                        (Integer) rs.getObject("level_5_dept_id"),
                        (Integer) rs.getObject("level_6_dept_id"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                parentLgdId,
                startDate,
                endDate,
                ChronoUnit.DAYS.between(startDate, endDate) + 1,
                topSchemeCount);
    }

    public List<SchemeSubmissionMetrics> getTopSchemeSubmissionMetricsByLgd(
            Integer tenantId,
            Integer parentLgdId,
            LocalDate startDate,
            LocalDate endDate,
            Integer limit,
            Integer offset,
            String sortBy,
            String sortDir) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(Math.min(lgdLevel + 1, 6));
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        String orderByClause = resolveDashboardOrderBy(sortBy, sortDir, true, daysInRange);

        String sql = String.format("""
                WITH scheme_rows_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.operating_status,
                        s.level_1_lgd_id,
                        s.level_2_lgd_id,
                        s.level_3_lgd_id,
                        s.level_4_lgd_id,
                        s.level_5_lgd_id,
                        s.level_6_lgd_id,
                        s.level_1_dept_id,
                        s.level_2_dept_id,
                        s.level_3_dept_id,
                        s.level_4_dept_id,
                        s.level_5_dept_id,
                        s.level_6_dept_id,
                        s.%2$s AS supplied_lgd_location_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (scheme_id)
                        scheme_id,
                        scheme_name,
                        operating_status,
                        level_1_lgd_id,
                        level_2_lgd_id,
                        level_3_lgd_id,
                        level_4_lgd_id,
                        level_5_lgd_id,
                        level_6_lgd_id,
                        level_1_dept_id,
                        level_2_dept_id,
                        level_3_dept_id,
                        level_4_dept_id,
                        level_5_dept_id,
                        level_6_dept_id
                    FROM scheme_rows_in_scope
                    ORDER BY scheme_id, supplied_lgd_location_id NULLS LAST
                ),
                scheme_supplied_lgd_locations AS (
                    SELECT DISTINCT scheme_id, supplied_lgd_location_id
                    FROM scheme_rows_in_scope
                    WHERE supplied_lgd_location_id IS NOT NULL
                ),
                supplied_lgd_locations AS (
                    SELECT
                        sll.scheme_id,
                        sll.supplied_lgd_location_id,
                        pl.lgd_c_name,
                        pl.title,
                        pl.lgd_level
                    FROM scheme_supplied_lgd_locations sll
                    LEFT JOIN analytics_schema.dim_lgd_location_table pl
                        ON pl.lgd_id = sll.supplied_lgd_location_id
                       AND pl.tenant_id = ?
                ),
                first_supplied_lgd_location AS (
                    SELECT DISTINCT ON (scheme_id)
                        scheme_id,
                        supplied_lgd_location_id,
                        lgd_c_name,
                        title,
                        lgd_level
                    FROM supplied_lgd_locations
                    ORDER BY scheme_id, LOWER(COALESCE(NULLIF(title, ''), lgd_c_name, '')), supplied_lgd_location_id
                ),
                supplied_lgd_location_summary AS (
                    SELECT
                        scheme_id,
                        ARRAY_AGG(supplied_lgd_location_id ORDER BY LOWER(COALESCE(NULLIF(title, ''), lgd_c_name, '')), supplied_lgd_location_id) AS supplied_lgd_location_ids,
                        ARRAY_AGG(lgd_c_name ORDER BY LOWER(COALESCE(NULLIF(title, ''), lgd_c_name, '')), supplied_lgd_location_id) AS supplied_lgd_location_c_names,
                        ARRAY_AGG(title ORDER BY LOWER(COALESCE(NULLIF(title, ''), lgd_c_name, '')), supplied_lgd_location_id) AS supplied_lgd_location_titles,
                        ARRAY_AGG(lgd_level ORDER BY LOWER(COALESCE(NULLIF(title, ''), lgd_c_name, '')), supplied_lgd_location_id) AS supplied_lgd_location_levels
                    FROM supplied_lgd_locations
                    GROUP BY scheme_id
                ),
                scheme_submission_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.operating_status AS operating_status,
                    COALESCE(sd.submission_days, 0)::int AS submission_days,
                    COALESCE(sd.total_water_supplied, 0)::bigint AS total_water_supplied,
                    fsl.supplied_lgd_location_id AS immediate_parent_lgd_id,
                    pl.lgd_c_name AS immediate_parent_lgd_c_name,
                    pl.title AS immediate_parent_lgd_title,
                    pl.lgd_level AS immediate_parent_lgd_level,
                    NULL::int AS immediate_parent_department_id,
                    NULL::varchar AS immediate_parent_department_c_name,
                    NULL::varchar AS immediate_parent_department_title,
                    NULL::int AS immediate_parent_department_level,
                    ss.level_1_lgd_id,
                    ss.level_2_lgd_id,
                    ss.level_3_lgd_id,
                    ss.level_4_lgd_id,
                    ss.level_5_lgd_id,
                    ss.level_6_lgd_id,
                    ss.level_1_dept_id,
                    ss.level_2_dept_id,
                    ss.level_3_dept_id,
                    ss.level_4_dept_id,
                    ss.level_5_dept_id,
                    ss.level_6_dept_id,
                    COALESCE(slls.supplied_lgd_location_ids, ARRAY[]::integer[]) AS supplied_lgd_location_ids,
                    COALESCE(slls.supplied_lgd_location_c_names, ARRAY[]::varchar[]) AS supplied_lgd_location_c_names,
                    COALESCE(slls.supplied_lgd_location_titles, ARRAY[]::varchar[]) AS supplied_lgd_location_titles,
                    COALESCE(slls.supplied_lgd_location_levels, ARRAY[]::integer[]) AS supplied_lgd_location_levels
                FROM schemes_in_scope ss
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = ss.scheme_id
                LEFT JOIN first_supplied_lgd_location fsl
                    ON fsl.scheme_id = ss.scheme_id
                LEFT JOIN analytics_schema.dim_lgd_location_table pl
                    ON pl.lgd_id = fsl.supplied_lgd_location_id
                   AND pl.tenant_id = ?
                LEFT JOIN supplied_lgd_location_summary slls
                    ON slls.scheme_id = ss.scheme_id
                ORDER BY %3$s
                LIMIT ?
                OFFSET ?
                """, schemeLgdColumn, childSchemeLgdColumn, orderByClause);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapSchemeSubmissionMetrics(rs),
                parentLgdId,
                tenantId,
                tenantId,
                startDate,
                endDate,
                tenantId,
                tenantId,
                limit,
                offset);
    }

    public void streamSchemeSubmissionMetricsByLgd(
            Integer tenantId,
            Integer parentLgdId,
            LocalDate startDate,
            LocalDate endDate,
            String sortBy,
            String sortDir,
            Consumer<SchemeSubmissionMetrics> consumer) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        String orderByClause = resolveDashboardOrderBy(sortBy, sortDir, true, daysInRange);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.operating_status,
                        s.level_1_lgd_id,
                        s.level_2_lgd_id,
                        s.level_3_lgd_id,
                        s.level_4_lgd_id,
                        s.level_5_lgd_id,
                        s.level_6_lgd_id,
                        s.level_1_dept_id,
                        s.level_2_dept_id,
                        s.level_3_dept_id,
                        s.level_4_dept_id,
                        s.level_5_dept_id,
                        s.level_6_dept_id,
                        CASE
                            WHEN s.level_6_lgd_id IS NOT NULL THEN s.level_5_lgd_id
                            WHEN s.level_5_lgd_id IS NOT NULL THEN s.level_4_lgd_id
                            WHEN s.level_4_lgd_id IS NOT NULL THEN s.level_3_lgd_id
                            WHEN s.level_3_lgd_id IS NOT NULL THEN s.level_2_lgd_id
                            WHEN s.level_2_lgd_id IS NOT NULL THEN s.level_1_lgd_id
                            WHEN s.level_1_lgd_id IS NOT NULL THEN s.parent_lgd_location_id
                            ELSE NULL
                        END AS immediate_parent_lgd_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_submission_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.operating_status AS operating_status,
                    COALESCE(sd.submission_days, 0)::int AS submission_days,
                    COALESCE(sd.total_water_supplied, 0)::bigint AS total_water_supplied,
                    ss.immediate_parent_lgd_id,
                    pl.lgd_c_name AS immediate_parent_lgd_c_name,
                    pl.title AS immediate_parent_lgd_title,
                    pl.lgd_level AS immediate_parent_lgd_level,
                    NULL::int AS immediate_parent_department_id,
                    NULL::varchar AS immediate_parent_department_c_name,
                    NULL::varchar AS immediate_parent_department_title,
                    NULL::int AS immediate_parent_department_level,
                    ss.level_1_lgd_id,
                    ss.level_2_lgd_id,
                    ss.level_3_lgd_id,
                    ss.level_4_lgd_id,
                    ss.level_5_lgd_id,
                    ss.level_6_lgd_id,
                    ss.level_1_dept_id,
                    ss.level_2_dept_id,
                    ss.level_3_dept_id,
                    ss.level_4_dept_id,
                    ss.level_5_dept_id,
                    ss.level_6_dept_id,
                    ARRAY[]::integer[] AS supplied_lgd_location_ids,
                    ARRAY[]::varchar[] AS supplied_lgd_location_c_names,
                    ARRAY[]::varchar[] AS supplied_lgd_location_titles,
                    ARRAY[]::integer[] AS supplied_lgd_location_levels
                FROM schemes_in_scope ss
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = ss.scheme_id
                LEFT JOIN analytics_schema.dim_lgd_location_table pl
                    ON pl.lgd_id = ss.immediate_parent_lgd_id
                   AND pl.tenant_id = ?
                ORDER BY %2$s
                """, schemeLgdColumn, orderByClause);

        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setFetchSize(EXPORT_FETCH_SIZE);
            int i = 1;
            ps.setInt(i++, parentLgdId);
            ps.setInt(i++, tenantId);
            ps.setObject(i++, startDate);
            ps.setObject(i++, endDate);
            ps.setInt(i++, tenantId);
            ps.setInt(i, tenantId);
            return ps;
        }, (RowCallbackHandler) rs -> consumer.accept(mapSchemeSubmissionMetrics(rs)));
    }

    public List<SchemeSubmissionMetrics> getTopSchemeSubmissionMetricsByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate, Integer topSchemeCount) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.operating_status,
                        s.level_1_lgd_id,
                        s.level_2_lgd_id,
                        s.level_3_lgd_id,
                        s.level_4_lgd_id,
                        s.level_5_lgd_id,
                        s.level_6_lgd_id,
                        s.level_1_dept_id,
                        s.level_2_dept_id,
                        s.level_3_dept_id,
                        s.level_4_dept_id,
                        s.level_5_dept_id,
                        s.level_6_dept_id,
                        CASE
                            WHEN s.level_6_dept_id IS NOT NULL THEN s.level_5_dept_id
                            WHEN s.level_5_dept_id IS NOT NULL THEN s.level_4_dept_id
                            WHEN s.level_4_dept_id IS NOT NULL THEN s.level_3_dept_id
                            WHEN s.level_3_dept_id IS NOT NULL THEN s.level_2_dept_id
                            WHEN s.level_2_dept_id IS NOT NULL THEN s.level_1_dept_id
                            WHEN s.level_1_dept_id IS NOT NULL THEN s.parent_department_location_id
                            ELSE NULL
                        END AS immediate_parent_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_submission_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.operating_status AS operating_status,
                    COALESCE(sd.submission_days, 0)::int AS submission_days,
                    COALESCE(sd.total_water_supplied, 0)::bigint AS total_water_supplied,
                    NULL::int AS immediate_parent_lgd_id,
                    NULL::varchar AS immediate_parent_lgd_c_name,
                    NULL::varchar AS immediate_parent_lgd_title,
                    NULL::int AS immediate_parent_lgd_level,
                    ss.immediate_parent_department_id,
                    pd.department_c_name AS immediate_parent_department_c_name,
                    pd.title AS immediate_parent_department_title,
                    pd.department_level AS immediate_parent_department_level,
                    ss.level_1_lgd_id,
                    ss.level_2_lgd_id,
                    ss.level_3_lgd_id,
                    ss.level_4_lgd_id,
                    ss.level_5_lgd_id,
                    ss.level_6_lgd_id,
                    ss.level_1_dept_id,
                    ss.level_2_dept_id,
                    ss.level_3_dept_id,
                    ss.level_4_dept_id,
                    ss.level_5_dept_id,
                    ss.level_6_dept_id,
                    ARRAY[]::integer[] AS supplied_lgd_location_ids,
                    ARRAY[]::varchar[] AS supplied_lgd_location_c_names,
                    ARRAY[]::varchar[] AS supplied_lgd_location_titles,
                    ARRAY[]::integer[] AS supplied_lgd_location_levels
                FROM schemes_in_scope ss
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = ss.scheme_id
                LEFT JOIN analytics_schema.dim_department_location_table pd
                    ON pd.department_id = ss.immediate_parent_department_id
                ORDER BY
                    (COALESCE(sd.submission_days, 0)::numeric / ?) DESC,
                    ss.scheme_id ASC
                LIMIT ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeSubmissionMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("operating_status"),
                        rs.getInt("submission_days"),
                        rs.getLong("total_water_supplied"),
                        (Integer) rs.getObject("immediate_parent_lgd_id"),
                        rs.getString("immediate_parent_lgd_c_name"),
                        rs.getString("immediate_parent_lgd_title"),
                        (Integer) rs.getObject("immediate_parent_lgd_level"),
                        (Integer) rs.getObject("immediate_parent_department_id"),
                        rs.getString("immediate_parent_department_c_name"),
                        rs.getString("immediate_parent_department_title"),
                        (Integer) rs.getObject("immediate_parent_department_level"),
                        (Integer) rs.getObject("level_1_lgd_id"),
                        (Integer) rs.getObject("level_2_lgd_id"),
                        (Integer) rs.getObject("level_3_lgd_id"),
                        (Integer) rs.getObject("level_4_lgd_id"),
                        (Integer) rs.getObject("level_5_lgd_id"),
                        (Integer) rs.getObject("level_6_lgd_id"),
                        (Integer) rs.getObject("level_1_dept_id"),
                        (Integer) rs.getObject("level_2_dept_id"),
                        (Integer) rs.getObject("level_3_dept_id"),
                        (Integer) rs.getObject("level_4_dept_id"),
                        (Integer) rs.getObject("level_5_dept_id"),
                        (Integer) rs.getObject("level_6_dept_id"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                parentDepartmentId,
                startDate,
                endDate,
                ChronoUnit.DAYS.between(startDate, endDate) + 1,
                topSchemeCount);
    }

    public List<SchemeSubmissionMetrics> getTopSchemeSubmissionMetricsByDepartment(
            Integer tenantId,
            Integer parentDepartmentId,
            LocalDate startDate,
            LocalDate endDate,
            Integer limit,
            Integer offset,
            String sortBy,
            String sortDir) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        String orderByClause = resolveDashboardOrderBy(sortBy, sortDir, false, daysInRange);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.operating_status,
                        s.level_1_lgd_id,
                        s.level_2_lgd_id,
                        s.level_3_lgd_id,
                        s.level_4_lgd_id,
                        s.level_5_lgd_id,
                        s.level_6_lgd_id,
                        s.level_1_dept_id,
                        s.level_2_dept_id,
                        s.level_3_dept_id,
                        s.level_4_dept_id,
                        s.level_5_dept_id,
                        s.level_6_dept_id,
                        CASE
                            WHEN s.level_6_dept_id IS NOT NULL THEN s.level_5_dept_id
                            WHEN s.level_5_dept_id IS NOT NULL THEN s.level_4_dept_id
                            WHEN s.level_4_dept_id IS NOT NULL THEN s.level_3_dept_id
                            WHEN s.level_3_dept_id IS NOT NULL THEN s.level_2_dept_id
                            WHEN s.level_2_dept_id IS NOT NULL THEN s.level_1_dept_id
                            WHEN s.level_1_dept_id IS NOT NULL THEN s.parent_department_location_id
                            ELSE NULL
                        END AS immediate_parent_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_submission_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.operating_status AS operating_status,
                    COALESCE(sd.submission_days, 0)::int AS submission_days,
                    COALESCE(sd.total_water_supplied, 0)::bigint AS total_water_supplied,
                    NULL::int AS immediate_parent_lgd_id,
                    NULL::varchar AS immediate_parent_lgd_c_name,
                    NULL::varchar AS immediate_parent_lgd_title,
                    NULL::int AS immediate_parent_lgd_level,
                    ss.immediate_parent_department_id,
                    pd.department_c_name AS immediate_parent_department_c_name,
                    pd.title AS immediate_parent_department_title,
                    pd.department_level AS immediate_parent_department_level,
                    ss.level_1_lgd_id,
                    ss.level_2_lgd_id,
                    ss.level_3_lgd_id,
                    ss.level_4_lgd_id,
                    ss.level_5_lgd_id,
                    ss.level_6_lgd_id,
                    ss.level_1_dept_id,
                    ss.level_2_dept_id,
                    ss.level_3_dept_id,
                    ss.level_4_dept_id,
                    ss.level_5_dept_id,
                    ss.level_6_dept_id,
                    ARRAY[]::integer[] AS supplied_lgd_location_ids,
                    ARRAY[]::varchar[] AS supplied_lgd_location_c_names,
                    ARRAY[]::varchar[] AS supplied_lgd_location_titles,
                    ARRAY[]::integer[] AS supplied_lgd_location_levels
                FROM schemes_in_scope ss
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = ss.scheme_id
                LEFT JOIN analytics_schema.dim_department_location_table pd
                    ON pd.department_id = ss.immediate_parent_department_id
                   AND pd.tenant_id = ?
                ORDER BY %2$s
                LIMIT ?
                OFFSET ?
                """, schemeDepartmentColumn, orderByClause);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapSchemeSubmissionMetrics(rs),
                parentDepartmentId,
                tenantId,
                startDate,
                endDate,
                tenantId,
                tenantId,
                limit,
                offset);
    }

    public void streamSchemeSubmissionMetricsByDepartment(
            Integer tenantId,
            Integer parentDepartmentId,
            LocalDate startDate,
            LocalDate endDate,
            String sortBy,
            String sortDir,
            Consumer<SchemeSubmissionMetrics> consumer) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        String orderByClause = resolveDashboardOrderBy(sortBy, sortDir, false, daysInRange);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.operating_status,
                        s.level_1_lgd_id,
                        s.level_2_lgd_id,
                        s.level_3_lgd_id,
                        s.level_4_lgd_id,
                        s.level_5_lgd_id,
                        s.level_6_lgd_id,
                        s.level_1_dept_id,
                        s.level_2_dept_id,
                        s.level_3_dept_id,
                        s.level_4_dept_id,
                        s.level_5_dept_id,
                        s.level_6_dept_id,
                        CASE
                            WHEN s.level_6_dept_id IS NOT NULL THEN s.level_5_dept_id
                            WHEN s.level_5_dept_id IS NOT NULL THEN s.level_4_dept_id
                            WHEN s.level_4_dept_id IS NOT NULL THEN s.level_3_dept_id
                            WHEN s.level_3_dept_id IS NOT NULL THEN s.level_2_dept_id
                            WHEN s.level_2_dept_id IS NOT NULL THEN s.level_1_dept_id
                            WHEN s.level_1_dept_id IS NOT NULL THEN s.parent_department_location_id
                            ELSE NULL
                        END AS immediate_parent_department_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_submission_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.operating_status AS operating_status,
                    COALESCE(sd.submission_days, 0)::int AS submission_days,
                    COALESCE(sd.total_water_supplied, 0)::bigint AS total_water_supplied,
                    NULL::int AS immediate_parent_lgd_id,
                    NULL::varchar AS immediate_parent_lgd_c_name,
                    NULL::varchar AS immediate_parent_lgd_title,
                    NULL::int AS immediate_parent_lgd_level,
                    ss.immediate_parent_department_id,
                    pd.department_c_name AS immediate_parent_department_c_name,
                    pd.title AS immediate_parent_department_title,
                    pd.department_level AS immediate_parent_department_level,
                    ss.level_1_lgd_id,
                    ss.level_2_lgd_id,
                    ss.level_3_lgd_id,
                    ss.level_4_lgd_id,
                    ss.level_5_lgd_id,
                    ss.level_6_lgd_id,
                    ss.level_1_dept_id,
                    ss.level_2_dept_id,
                    ss.level_3_dept_id,
                    ss.level_4_dept_id,
                    ss.level_5_dept_id,
                    ss.level_6_dept_id,
                    ARRAY[]::integer[] AS supplied_lgd_location_ids,
                    ARRAY[]::varchar[] AS supplied_lgd_location_c_names,
                    ARRAY[]::varchar[] AS supplied_lgd_location_titles,
                    ARRAY[]::integer[] AS supplied_lgd_location_levels
                FROM schemes_in_scope ss
                LEFT JOIN scheme_submission_days sd
                    ON sd.scheme_id = ss.scheme_id
                LEFT JOIN analytics_schema.dim_department_location_table pd
                    ON pd.department_id = ss.immediate_parent_department_id
                   AND pd.tenant_id = ?
                ORDER BY %2$s
                """, schemeDepartmentColumn, orderByClause);

        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setFetchSize(EXPORT_FETCH_SIZE);
            int i = 1;
            ps.setInt(i++, parentDepartmentId);
            ps.setInt(i++, tenantId);
            ps.setObject(i++, startDate);
            ps.setObject(i++, endDate);
            ps.setInt(i++, tenantId);
            ps.setInt(i, tenantId);
            return ps;
        }, (RowCallbackHandler) rs -> consumer.accept(mapSchemeSubmissionMetrics(rs)));
    }

    public List<SchemeRegularityListMetrics> getSchemeRegionReportByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevel(parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.state_scheme_id,
                        s.centre_scheme_id,
                        s.operating_status AS operating_status
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading >= 0 THEN m.reading_date END)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.state_scheme_id,
                    ss.centre_scheme_id,
                    ss.operating_status,
                    COALESCE(sd.supply_days, 0)::int AS supply_days,
                    COALESCE(sd.submission_days, 0)::int AS submission_days
                FROM schemes_in_scope ss
                LEFT JOIN scheme_days sd
                    ON sd.scheme_id = ss.scheme_id
                ORDER BY ss.scheme_id
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeRegularityListMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        (Integer) rs.getObject("operating_status"),
                        rs.getInt("supply_days"),
                        rs.getInt("submission_days")),
                parentLgdId,
                startDate,
                endDate);
    }

    public List<SchemeRegularityListMetrics> getSchemeRegionReportByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.state_scheme_id,
                        s.centre_scheme_id,
                        s.operating_status AS operating_status
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading >= 0 THEN m.reading_date END)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.state_scheme_id,
                    ss.centre_scheme_id,
                    ss.operating_status,
                    COALESCE(sd.supply_days, 0)::int AS supply_days,
                    COALESCE(sd.submission_days, 0)::int AS submission_days
                FROM schemes_in_scope ss
                LEFT JOIN scheme_days sd
                    ON sd.scheme_id = ss.scheme_id
                ORDER BY ss.scheme_id
                """, schemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeRegularityListMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        (Integer) rs.getObject("operating_status"),
                        rs.getInt("supply_days"),
                        rs.getInt("submission_days")),
                parentLgdId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<SchemeRegularityListMetrics> getSchemeRegionReportByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevel(parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.state_scheme_id,
                        s.centre_scheme_id,
                        s.operating_status AS operating_status
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                scheme_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading >= 0 THEN m.reading_date END)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.state_scheme_id,
                    ss.centre_scheme_id,
                    ss.operating_status,
                    COALESCE(sd.supply_days, 0)::int AS supply_days,
                    COALESCE(sd.submission_days, 0)::int AS submission_days
                FROM schemes_in_scope ss
                LEFT JOIN scheme_days sd
                    ON sd.scheme_id = ss.scheme_id
                ORDER BY ss.scheme_id
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeRegularityListMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        (Integer) rs.getObject("operating_status"),
                        rs.getInt("supply_days"),
                        rs.getInt("submission_days")),
                parentDepartmentId,
                startDate,
                endDate);
    }

    public List<SchemeRegularityListMetrics> getSchemeRegionReportByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                WITH schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.scheme_name,
                        s.state_scheme_id,
                        s.centre_scheme_id,
                        s.operating_status AS operating_status
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                scheme_days AS (
                    SELECT
                        m.scheme_id,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END)::int AS supply_days,
                        COUNT(DISTINCT CASE WHEN m.confirmed_reading >= 0 THEN m.reading_date END)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope ss
                        ON ss.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    ss.scheme_id,
                    ss.scheme_name,
                    ss.state_scheme_id,
                    ss.centre_scheme_id,
                    ss.operating_status,
                    COALESCE(sd.supply_days, 0)::int AS supply_days,
                    COALESCE(sd.submission_days, 0)::int AS submission_days
                FROM schemes_in_scope ss
                LEFT JOIN scheme_days sd
                    ON sd.scheme_id = ss.scheme_id
                ORDER BY ss.scheme_id
                """, schemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeRegularityListMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        (Integer) rs.getObject("state_scheme_id"),
                        (Integer) rs.getObject("centre_scheme_id"),
                        (Integer) rs.getObject("operating_status"),
                        rs.getInt("supply_days"),
                        rs.getInt("submission_days")),
                parentDepartmentId,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public String getParentLgdCNameByLgd(Integer lgdId) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT MAX(l.lgd_c_name) AS parent_lgd_c_name
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.lgd_id = s.parent_lgd_location_id
                WHERE s.%1$s = ?
                """, schemeLgdColumn);

        return jdbcTemplate.queryForObject(sql, String.class, lgdId);
    }

    public String getParentLgdCNameByLgd(Integer tenantId, Integer lgdId) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT MAX(l.lgd_c_name) AS parent_lgd_c_name
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.lgd_id = s.parent_lgd_location_id
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                  AND l.tenant_id = ?
                """, schemeLgdColumn);

        return jdbcTemplate.queryForObject(sql, String.class, lgdId, tenantId, tenantId);
    }

    public String getParentLgdTitleByLgd(Integer lgdId) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT MAX(l.title) AS parent_lgd_title
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.lgd_id = s.parent_lgd_location_id
                WHERE s.%1$s = ?
                """, schemeLgdColumn);

        return jdbcTemplate.queryForObject(sql, String.class, lgdId);
    }

    public String getParentLgdTitleByLgd(Integer tenantId, Integer lgdId) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);

        String sql = String.format("""
                SELECT MAX(l.title) AS parent_lgd_title
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.lgd_id = s.parent_lgd_location_id
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                  AND l.tenant_id = ?
                """, schemeLgdColumn);

        return jdbcTemplate.queryForObject(sql, String.class, lgdId, tenantId, tenantId);
    }

    public String getParentDepartmentCNameByDepartment(Integer departmentId) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT MAX(d.department_c_name) AS parent_department_c_name
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_department_location_table d
                    ON d.department_id = s.parent_department_location_id
                WHERE s.%1$s = ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.queryForObject(sql, String.class, departmentId);
    }

    public String getParentDepartmentCNameByDepartment(Integer tenantId, Integer departmentId) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT MAX(d.department_c_name) AS parent_department_c_name
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_department_location_table d
                    ON d.department_id = s.parent_department_location_id
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                  AND d.tenant_id = ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.queryForObject(sql, String.class, departmentId, tenantId, tenantId);
    }

    public String getParentDepartmentTitleByDepartment(Integer departmentId) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT MAX(d.title) AS parent_department_title
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_department_location_table d
                    ON d.department_id = s.parent_department_location_id
                WHERE s.%1$s = ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.queryForObject(sql, String.class, departmentId);
    }

    public String getParentDepartmentTitleByDepartment(Integer tenantId, Integer departmentId) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);

        String sql = String.format("""
                SELECT MAX(d.title) AS parent_department_title
                FROM analytics_schema.dim_scheme_table s
                LEFT JOIN analytics_schema.dim_department_location_table d
                    ON d.department_id = s.parent_department_location_id
                WHERE s.%1$s = ?
                  AND s.tenant_id = ?
                  AND d.tenant_id = ?
                """, schemeDepartmentColumn);

        return jdbcTemplate.queryForObject(sql, String.class, departmentId, tenantId, tenantId);
    }

    public List<SchemeWaterSupplyMetrics> getAverageWaterSupplyPerCurrentRegion(
            Integer tenantId, LocalDate startDate, LocalDate endDate) {
        long daysInRange = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (daysInRange <= 0) {
            return List.of();
        }
        String sql = """
                WITH schemes_in_tenant AS (
                    SELECT DISTINCT ON (s.scheme_id)
                        s.scheme_id,
                        s.scheme_name,
                        s.house_hold_count::bigint AS house_hold_count,
                        COALESCE(s.fhtc_count, 0)::bigint AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0)::bigint AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.tenant_id = ?
                      AND s.house_hold_count IS NOT NULL
                      AND s.house_hold_count > 0
                    ORDER BY s.scheme_id, COALESCE(s.fhtc_count, 0) DESC, s.house_hold_count DESC, COALESCE(s.planned_fhtc, 0) DESC
                )
                SELECT
                    s.scheme_id,
                    s.scheme_name,
                    s.house_hold_count,
                    s.fhtc_count,
                    s.planned_fhtc,
                    COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                        AS total_water_supplied_liters,
                    COALESCE(COUNT(DISTINCT CASE WHEN m.confirmed_reading > 0 THEN m.reading_date END), 0)::int
                        AS supply_days
                FROM schemes_in_tenant s
                LEFT JOIN analytics_schema.fact_meter_reading_table m
                    ON m.scheme_id = s.scheme_id
                    AND m.tenant_id = ?
                    AND m.reading_date BETWEEN ? AND ?
                GROUP BY s.scheme_id, s.scheme_name, s.house_hold_count, s.fhtc_count, s.planned_fhtc
                ORDER BY s.scheme_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeWaterSupplyMetrics(
                        rs.getInt("scheme_id"),
                        rs.getString("scheme_name"),
                        rs.getLong("house_hold_count"),
                        rs.getLong("fhtc_count"),
                        rs.getLong("planned_fhtc"),
                        rs.getLong("total_water_supplied_liters"),
                        rs.getInt("supply_days"),
                        BigDecimal.valueOf(rs.getLong("total_water_supplied_liters"))
                                .divide(
                                        BigDecimal.valueOf(rs.getLong("house_hold_count") * daysInRange),
                                        4,
                                        java.math.RoundingMode.HALF_UP)),
                tenantId,
                tenantId,
                startDate,
                endDate);
    }

    public List<ChildRegionWaterSupplyMetrics> getAverageWaterSupplyPerNation(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH water_by_scheme AS (
                    SELECT
                        f.tenant_id,
                        f.scheme_id,
                        COALESCE(SUM(
                            CASE
                                WHEN (f.submission_status = 1 OR f.submission_status IS NULL)
                                     AND f.water_quantity > 0
                                    THEN f.water_quantity
                                ELSE 0
                            END
                        ), 0)::bigint AS total_water_supplied_liters
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.tenant_id, f.scheme_id
                )
                SELECT
                    t.tenant_id,
                    t.state_code,
                    t.title,
                    COALESCE(SUM(COALESCE(s.house_hold_count, 0)), 0)::bigint AS total_household_count,
                    COALESCE(SUM(COALESCE(s.fhtc_count, 0)), 0)::bigint AS total_fhtc_count,
                    COALESCE(SUM(COALESCE(s.planned_fhtc, 0)), 0)::bigint AS total_planned_fhtc,
                    COALESCE(SUM(w.total_water_supplied_liters), 0)::bigint AS total_water_supplied_liters,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    CASE
                        WHEN COUNT(DISTINCT s.scheme_id) > 0
                            THEN ROUND(COALESCE(SUM(w.total_water_supplied_liters), 0)::numeric / COUNT(DISTINCT s.scheme_id), 4)
                        ELSE 0::numeric
                    END AS avg_water_supply_per_scheme
                FROM analytics_schema.dim_tenant_table t
                LEFT JOIN (
                    SELECT DISTINCT ON (tenant_id, scheme_id) tenant_id, scheme_id, house_hold_count, fhtc_count, planned_fhtc
                    FROM analytics_schema.dim_scheme_table
                    ORDER BY tenant_id, scheme_id, fhtc_count DESC NULLS LAST, house_hold_count DESC NULLS LAST, planned_fhtc DESC NULLS LAST
                ) s
                    ON s.tenant_id = t.tenant_id
                LEFT JOIN water_by_scheme w
                    ON w.tenant_id = s.tenant_id
                    AND w.scheme_id = s.scheme_id
                WHERE t.tenant_id > 0
                GROUP BY t.tenant_id, t.state_code, t.title
                ORDER BY t.tenant_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterSupplyMetrics(
                        rs.getInt("tenant_id"),
                        rs.getString("state_code"),
                        null,
                        null,
                        rs.getString("title"),
                        rs.getLong("total_household_count"),
                        rs.getLong("total_fhtc_count"),
                        rs.getLong("total_planned_fhtc"),
                        rs.getLong("total_water_supplied_liters"),
                        rs.getInt("scheme_count"),
                        rs.getBigDecimal("avg_water_supply_per_scheme")),
                startDate,
                endDate);
    }

    public List<TenantSupplyDaysInEfficientRange> getTenantWiseSupplyDaysInEfficientRange(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH tenant_cfg AS (
                    SELECT
                        t.tenant_id,
                        COALESCE(t.required_lpcd, 0) AS required_lpcd,
                        COALESCE(t.person_count_per_household, 5) AS person_count_per_household,
                        COALESCE(t.over_supply_range_percentage, 0) AS over_supply_range_percentage,
                        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_table t
                    WHERE t.tenant_id > 0
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.tenant_id, s.scheme_id)
                        s.tenant_id,
                        s.scheme_id,
                        COALESCE(s.fhtc_count, 0)::bigint AS fhtc_count
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.tenant_id > 0
                    ORDER BY s.tenant_id, s.scheme_id, COALESCE(s.fhtc_count, 0) DESC
                ),
                dates_in_range AS (
                    SELECT d.full_date AS date
                    FROM analytics_schema.dim_date_table d
                    WHERE d.full_date BETWEEN ? AND ?
                ),
                ewater_by_scheme_day AS (
                    SELECT
                        f.tenant_id,
                        f.scheme_id,
                        f.date,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.tenant_id, f.scheme_id, f.date
                ),
                tenant_supply_days AS (
                    SELECT
                        s.tenant_id,
                        COALESCE(SUM(
                            CASE
                                WHEN COALESCE(wd.daily_ewater_quantity, 0)::numeric BETWEEN
                                     (
                                         (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 - (tc.under_supply_range_percentage::numeric / 100))
                                     )
                                     AND
                                     (
                                         (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 + (tc.over_supply_range_percentage::numeric / 100))
                                     )
                                    THEN 1
                                ELSE 0
                            END
                        ), 0)::bigint AS supply_days_in_efficient_range
                    FROM schemes_in_scope s
                    CROSS JOIN dates_in_range dr
                    LEFT JOIN ewater_by_scheme_day wd
                        ON wd.tenant_id = s.tenant_id
                        AND wd.scheme_id = s.scheme_id
                        AND wd.date = dr.date
                    JOIN tenant_cfg tc
                        ON tc.tenant_id = s.tenant_id
                    GROUP BY s.tenant_id
                )
                SELECT
                    tc.tenant_id,
                    COALESCE(tsd.supply_days_in_efficient_range, 0)::bigint AS supply_days_in_efficient_range
                FROM tenant_cfg tc
                LEFT JOIN tenant_supply_days tsd
                    ON tsd.tenant_id = tc.tenant_id
                ORDER BY tc.tenant_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new TenantSupplyDaysInEfficientRange(
                        rs.getInt("tenant_id"),
                        rs.getLong("supply_days_in_efficient_range")),
                startDate,
                endDate,
                startDate,
                endDate);
    }

    public List<NationalDashboardTenantStateMetadata> getNationalDashboardTenantStateMetadata() {
        String sql = """
                SELECT DISTINCT ON (t.tenant_id)
                    t.tenant_id,
                    l.lgd_id,
                    t.status AS tenant_status
                FROM analytics_schema.dim_tenant_table t
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.tenant_id = t.tenant_id
                   AND l.lgd_level = 1
                WHERE t.tenant_id > 0
                ORDER BY t.tenant_id, l.lgd_id NULLS LAST
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Integer lgdId = rs.getObject("lgd_id") == null ? null : rs.getInt("lgd_id");
            return new NationalDashboardTenantStateMetadata(
                    rs.getInt("tenant_id"),
                    lgdId,
                    rs.getInt("tenant_status"));
        });
    }

    public List<NationalDashboardStateBoundary> getNationalDashboardStateBoundaries() {
        String sql = """
                SELECT DISTINCT ON (t.tenant_id)
                    t.tenant_id,
                    l.lgd_id,
                    t.status AS tenant_status,
                    t.state_code,
                    t.title AS state_title,
                    CASE
                        WHEN l.geom IS NOT NULL THEN ST_AsGeoJSON(l.geom, 9, 8)
                        ELSE NULL
                    END AS boundary_geojson
                FROM analytics_schema.dim_tenant_table t
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.tenant_id = t.tenant_id
                   AND l.lgd_level = 1
                WHERE t.tenant_id > 0
                ORDER BY t.tenant_id, l.lgd_id NULLS LAST
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Integer lgdId = rs.getObject("lgd_id") == null ? null : rs.getInt("lgd_id");
            return new NationalDashboardStateBoundary(
                    rs.getInt("tenant_id"),
                    lgdId,
                    rs.getInt("tenant_status"),
                    rs.getString("state_code"),
                    rs.getString("state_title"),
                    rs.getString("boundary_geojson"));
        });
    }

    public List<NationalDashboardLevel2LgdBoundary> getNationalDashboardLevel2LgdBoundaries() {
        String sql = """
                SELECT
                    l.tenant_id,
                    l.lgd_id,
                    t.status AS tenant_status,
                    t.state_code,
                    t.title AS state_title,
                    l.title AS lgd_title,
                    CASE
                        WHEN l.geom IS NOT NULL THEN ST_AsGeoJSON(l.geom, 9, 8)
                        ELSE NULL
                    END AS boundary_geojson
                FROM analytics_schema.dim_lgd_location_table l
                JOIN analytics_schema.dim_tenant_table t
                    ON t.tenant_id = l.tenant_id
                WHERE l.tenant_id > 0
                  AND l.lgd_level = 2
                ORDER BY l.tenant_id, l.lgd_id
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Integer lgdId = rs.getObject("lgd_id") == null ? null : rs.getInt("lgd_id");
            return new NationalDashboardLevel2LgdBoundary(
                    rs.getInt("tenant_id"),
                    lgdId,
                    rs.getInt("tenant_status"),
                    rs.getString("state_code"),
                    rs.getString("state_title"),
                    rs.getString("lgd_title"),
                    rs.getString("boundary_geojson"));
        });
    }

    public String getNationalBoundaryGeoJson() {
        String sql = """
                SELECT
                    CASE
                        WHEN l.geom IS NOT NULL THEN ST_AsGeoJSON(l.geom, 9, 8)
                        ELSE NULL
                    END AS boundary_geojson
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.tenant_id = 0
                  AND l.lgd_c_name = 'Country'
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("boundary_geojson"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<StateSchemeRegularityMetrics> getStateWiseRegularityMetrics(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH supply_days_by_scheme AS (
                    SELECT
                        m.tenant_id,
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.tenant_id, m.scheme_id
                )
                SELECT
                    t.tenant_id,
                    t.state_code,
                    t.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.supply_days), 0)::int AS total_supply_days
                FROM analytics_schema.dim_tenant_table t
                LEFT JOIN (
                    SELECT DISTINCT tenant_id, scheme_id
                    FROM analytics_schema.dim_scheme_table
                ) s
                    ON s.tenant_id = t.tenant_id
                LEFT JOIN supply_days_by_scheme sd
                    ON sd.tenant_id = s.tenant_id
                    AND sd.scheme_id = s.scheme_id
                WHERE t.tenant_id > 0
                GROUP BY t.tenant_id, t.state_code, t.title
                ORDER BY t.tenant_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new StateSchemeRegularityMetrics(
                        rs.getInt("tenant_id"),
                        rs.getString("state_code"),
                        rs.getString("title"),
                        rs.getInt("scheme_count"),
                        rs.getInt("total_supply_days")),
                startDate,
                endDate);
    }

    public List<StateReadingSubmissionMetrics> getStateWiseReadingSubmissionMetrics(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH submission_days_by_scheme AS (
                    SELECT
                        m.tenant_id,
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                    GROUP BY m.tenant_id, m.scheme_id
                )
                SELECT
                    t.tenant_id,
                    t.state_code,
                    t.title,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.submission_days), 0)::int AS total_submission_days
                FROM analytics_schema.dim_tenant_table t
                LEFT JOIN (
                    SELECT DISTINCT tenant_id, scheme_id
                    FROM analytics_schema.dim_scheme_table
                ) s
                    ON s.tenant_id = t.tenant_id
                LEFT JOIN submission_days_by_scheme sd
                    ON sd.tenant_id = s.tenant_id
                    AND sd.scheme_id = s.scheme_id
                WHERE t.tenant_id > 0
                GROUP BY t.tenant_id, t.state_code, t.title
                ORDER BY t.tenant_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new StateReadingSubmissionMetrics(
                        rs.getInt("tenant_id"),
                        rs.getString("state_code"),
                        rs.getString("title"),
                        rs.getInt("scheme_count"),
                        rs.getInt("total_submission_days")),
                startDate,
                endDate);
    }

    public List<Level2WaterSupplyMetrics> getLgdLevel2WiseWaterSupplyMetricsForNation(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH water_by_scheme AS (
                    SELECT
                        f.tenant_id,
                        f.scheme_id,
                        COALESCE(SUM(
                            CASE
                                WHEN (f.submission_status = 1 OR f.submission_status IS NULL)
                                     AND f.water_quantity > 0
                                    THEN f.water_quantity
                                ELSE 0
                            END
                        ), 0)::bigint AS total_water_supplied_liters
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.tenant_id, f.scheme_id
                )
                SELECT
                    t.tenant_id,
                    t.status AS tenant_status,
                    t.state_code,
                    t.title AS state_title,
                    s.level_2_lgd_id AS lgd_id,
                    l.title AS district_title,
                    COALESCE(SUM(COALESCE(s.house_hold_count, 0)), 0)::bigint AS total_household_count,
                    COALESCE(SUM(COALESCE(s.fhtc_count, 0)), 0)::bigint AS total_fhtc_count,
                    COALESCE(SUM(COALESCE(s.planned_fhtc, 0)), 0)::bigint AS total_planned_fhtc,
                    COALESCE(SUM(w.total_water_supplied_liters), 0)::bigint AS total_water_supplied_liters,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    CASE
                        WHEN COUNT(DISTINCT s.scheme_id) > 0
                            THEN ROUND(COALESCE(SUM(w.total_water_supplied_liters), 0)::numeric / COUNT(DISTINCT s.scheme_id), 4)
                        ELSE 0::numeric
                    END AS avg_water_supply_per_scheme
                FROM (
                    SELECT DISTINCT ON (tenant_id, scheme_id, level_2_lgd_id) tenant_id, scheme_id, level_2_lgd_id,
                           house_hold_count, fhtc_count, planned_fhtc
                    FROM analytics_schema.dim_scheme_table
                    ORDER BY tenant_id, scheme_id, level_2_lgd_id, fhtc_count DESC NULLS LAST, house_hold_count DESC NULLS LAST, planned_fhtc DESC NULLS LAST
                ) s
                JOIN analytics_schema.dim_tenant_table t
                    ON t.tenant_id = s.tenant_id
                LEFT JOIN analytics_schema.dim_lgd_location_table l
                    ON l.tenant_id = s.tenant_id
                   AND l.lgd_id = s.level_2_lgd_id
                   AND l.lgd_level = 2
                LEFT JOIN water_by_scheme w
                    ON w.tenant_id = s.tenant_id
                   AND w.scheme_id = s.scheme_id
                WHERE s.tenant_id > 0
                  AND s.level_2_lgd_id IS NOT NULL
                GROUP BY
                    t.tenant_id,
                    t.status,
                    t.state_code,
                    t.title,
                    s.level_2_lgd_id,
                    l.title
                ORDER BY t.tenant_id, s.level_2_lgd_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Level2WaterSupplyMetrics(
                        rs.getInt("tenant_id"),
                        rs.getInt("tenant_status"),
                        rs.getString("state_code"),
                        rs.getString("state_title"),
                        (Integer) rs.getObject("lgd_id"),
                        rs.getString("district_title"),
                        rs.getLong("total_household_count"),
                        rs.getLong("total_fhtc_count"),
                        rs.getLong("total_planned_fhtc"),
                        rs.getLong("total_water_supplied_liters"),
                        rs.getInt("scheme_count"),
                        rs.getBigDecimal("avg_water_supply_per_scheme")),
                startDate,
                endDate);
    }

    public List<Level2SupplyDaysInEfficientRange> getLgdLevel2WiseSupplyDaysInEfficientRangeForNation(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH tenant_cfg AS (
                    SELECT
                        t.tenant_id,
                        COALESCE(t.required_lpcd, 0) AS required_lpcd,
                        COALESCE(t.person_count_per_household, 5) AS person_count_per_household,
                        COALESCE(t.over_supply_range_percentage, 0) AS over_supply_range_percentage,
                        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_table t
                    WHERE t.tenant_id > 0
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.tenant_id, s.scheme_id, s.level_2_lgd_id)
                        s.tenant_id,
                        s.scheme_id,
                        s.level_2_lgd_id,
                        COALESCE(s.fhtc_count, 0)::bigint AS fhtc_count
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.tenant_id > 0
                      AND s.level_2_lgd_id IS NOT NULL
                    ORDER BY s.tenant_id, s.scheme_id, s.level_2_lgd_id, COALESCE(s.fhtc_count, 0) DESC
                ),
                dates_in_range AS (
                    SELECT d.full_date AS date
                    FROM analytics_schema.dim_date_table d
                    WHERE d.full_date BETWEEN ? AND ?
                ),
                ewater_by_scheme_day AS (
                    SELECT
                        f.tenant_id,
                        f.scheme_id,
                        f.date,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.tenant_id, f.scheme_id, f.date
                ),
                level2_supply_days AS (
                    SELECT
                        s.tenant_id,
                        s.level_2_lgd_id AS lgd_id,
                        COALESCE(SUM(
                            CASE
                                WHEN COALESCE(wd.daily_ewater_quantity, 0)::numeric BETWEEN
                                     (
                                         (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 - (tc.under_supply_range_percentage::numeric / 100))
                                     )
                                     AND
                                     (
                                         (tc.required_lpcd::numeric * (s.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 + (tc.over_supply_range_percentage::numeric / 100))
                                     )
                                    THEN 1
                                ELSE 0
                            END
                        ), 0)::bigint AS supply_days_in_efficient_range
                    FROM schemes_in_scope s
                    CROSS JOIN dates_in_range dr
                    LEFT JOIN ewater_by_scheme_day wd
                        ON wd.tenant_id = s.tenant_id
                       AND wd.scheme_id = s.scheme_id
                       AND wd.date = dr.date
                    JOIN tenant_cfg tc
                        ON tc.tenant_id = s.tenant_id
                    GROUP BY s.tenant_id, s.level_2_lgd_id
                )
                SELECT
                    tenant_id,
                    lgd_id,
                    supply_days_in_efficient_range
                FROM level2_supply_days
                ORDER BY tenant_id, lgd_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Level2SupplyDaysInEfficientRange(
                        rs.getInt("tenant_id"),
                        (Integer) rs.getObject("lgd_id"),
                        rs.getLong("supply_days_in_efficient_range")),
                startDate,
                endDate,
                startDate,
                endDate);
    }

    public List<Level2RegularityMetrics> getLgdLevel2WiseRegularityMetricsForNation(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH supply_days_by_scheme AS (
                    SELECT
                        m.tenant_id,
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS supply_days
                    FROM analytics_schema.fact_meter_reading_table m
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.tenant_id, m.scheme_id
                )
                SELECT
                    s.tenant_id,
                    s.level_2_lgd_id AS lgd_id,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.supply_days), 0)::int AS total_supply_days
                FROM (
                    SELECT DISTINCT tenant_id, scheme_id, level_2_lgd_id
                    FROM analytics_schema.dim_scheme_table
                ) s
                LEFT JOIN supply_days_by_scheme sd
                    ON sd.tenant_id = s.tenant_id
                   AND sd.scheme_id = s.scheme_id
                WHERE s.tenant_id > 0
                  AND s.level_2_lgd_id IS NOT NULL
                GROUP BY s.tenant_id, s.level_2_lgd_id
                ORDER BY s.tenant_id, s.level_2_lgd_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Level2RegularityMetrics(
                        rs.getInt("tenant_id"),
                        (Integer) rs.getObject("lgd_id"),
                        rs.getInt("scheme_count"),
                        rs.getInt("total_supply_days")),
                startDate,
                endDate);
    }

    public List<Level2ReadingSubmissionMetrics> getLgdLevel2WiseReadingSubmissionMetricsForNation(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                WITH submission_days_by_scheme AS (
                    SELECT
                        m.tenant_id,
                        m.scheme_id,
                        COUNT(DISTINCT m.reading_date)::int AS submission_days
                    FROM analytics_schema.fact_meter_reading_table m
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading >= 0
                    GROUP BY m.tenant_id, m.scheme_id
                )
                SELECT
                    s.tenant_id,
                    s.level_2_lgd_id AS lgd_id,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    COALESCE(SUM(sd.submission_days), 0)::int AS total_submission_days
                FROM (
                    SELECT DISTINCT tenant_id, scheme_id, level_2_lgd_id
                    FROM analytics_schema.dim_scheme_table
                ) s
                LEFT JOIN submission_days_by_scheme sd
                    ON sd.tenant_id = s.tenant_id
                   AND sd.scheme_id = s.scheme_id
                WHERE s.tenant_id > 0
                  AND s.level_2_lgd_id IS NOT NULL
                GROUP BY s.tenant_id, s.level_2_lgd_id
                ORDER BY s.tenant_id, s.level_2_lgd_id
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new Level2ReadingSubmissionMetrics(
                        rs.getInt("tenant_id"),
                        (Integer) rs.getObject("lgd_id"),
                        rs.getInt("scheme_count"),
                        rs.getInt("total_submission_days")),
                startDate,
                endDate);
    }

    public List<OutageReasonSchemeCount> getOverallOutageReasonSchemeCount(
            LocalDate startDate, LocalDate endDate) {
        String sql = """
                SELECT
                    f.outage_reason,
                    COUNT(DISTINCT (f.tenant_id, f.scheme_id))::int AS scheme_count
                FROM analytics_schema.fact_water_quantity_table f
                WHERE f.outage_reason IS NOT NULL
                  AND f.date BETWEEN ? AND ?
                GROUP BY f.outage_reason
                ORDER BY f.outage_reason
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new OutageReasonSchemeCount(
                        rs.getString("outage_reason"),
                        rs.getInt("scheme_count")),
                startDate,
                endDate);
    }

    public List<ChildRegionWaterSupplyMetrics> getAverageWaterSupplyPerCurrentRegionByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        if (lgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + lgdId);
        }

        int childLevel = lgdLevel + 1;
        String parentSchemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(lgdLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.tenant_id = ?
                      AND l.lgd_level = ?
                      AND l.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id, s.%2$s)
                        s.scheme_id,
                        s.%2$s AS child_lgd_id,
                        COALESCE(s.house_hold_count, 0) AS house_hold_count,
                        COALESCE(s.fhtc_count, 0) AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0) AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.tenant_id = ?
                      AND s.%3$s = ?
                    ORDER BY s.scheme_id, s.%2$s, COALESCE(s.fhtc_count, 0) DESC, COALESCE(s.house_hold_count, 0) DESC, COALESCE(s.planned_fhtc, 0) DESC
                ),
                water_by_scheme AS (
                    SELECT
                        m.scheme_id,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied_liters
                    FROM analytics_schema.fact_meter_reading_table m
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(SUM(s.house_hold_count), 0)::bigint AS total_household_count,
                    COALESCE(SUM(s.fhtc_count), 0)::bigint AS total_fhtc_count,
                    COALESCE(SUM(s.planned_fhtc), 0)::bigint AS total_planned_fhtc,
                    COALESCE(SUM(w.total_water_supplied_liters), 0)::bigint AS total_water_supplied_liters,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    CASE
                        WHEN COUNT(DISTINCT s.scheme_id) > 0
                            THEN ROUND(COALESCE(SUM(w.total_water_supplied_liters), 0)::numeric / COUNT(DISTINCT s.scheme_id), 4)
                        ELSE 0::numeric
                    END AS avg_water_supply_per_scheme
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_lgd_id = c.child_lgd_id
                LEFT JOIN water_by_scheme w
                    ON w.scheme_id = s.scheme_id
                GROUP BY c.child_lgd_id, c.title
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterSupplyMetrics(
                        null,
                        null,
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("title"),
                        rs.getLong("total_household_count"),
                        rs.getLong("total_fhtc_count"),
                        rs.getLong("total_planned_fhtc"),
                        rs.getLong("total_water_supplied_liters"),
                        rs.getInt("scheme_count"),
                        rs.getBigDecimal("avg_water_supply_per_scheme")),
                tenantId,
                childLevel,
                lgdId,
                tenantId,
                lgdId,
                tenantId,
                startDate,
                endDate);
    }

    public List<ChildRegionWaterSupplyMetrics> getAverageWaterSupplyPerCurrentRegionByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (departmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int childLevel = departmentLevel + 1;
        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(departmentLevel);

        String sql = String.format("""
                WITH child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.tenant_id = ?
                      AND d.department_level = ?
                      AND d.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id, s.%2$s)
                        s.scheme_id,
                        s.%2$s AS child_department_id,
                        COALESCE(s.house_hold_count, 0) AS house_hold_count,
                        COALESCE(s.fhtc_count, 0) AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0) AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.tenant_id = ?
                      AND s.%3$s = ?
                    ORDER BY s.scheme_id, s.%2$s, COALESCE(s.fhtc_count, 0) DESC, COALESCE(s.house_hold_count, 0) DESC, COALESCE(s.planned_fhtc, 0) DESC
                ),
                water_by_scheme AS (
                    SELECT
                        m.scheme_id,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint
                            AS total_water_supplied_liters
                    FROM analytics_schema.fact_meter_reading_table m
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(SUM(s.house_hold_count), 0)::bigint AS total_household_count,
                    COALESCE(SUM(s.fhtc_count), 0)::bigint AS total_fhtc_count,
                    COALESCE(SUM(s.planned_fhtc), 0)::bigint AS total_planned_fhtc,
                    COALESCE(SUM(w.total_water_supplied_liters), 0)::bigint AS total_water_supplied_liters,
                    COALESCE(COUNT(DISTINCT s.scheme_id), 0)::int AS scheme_count,
                    CASE
                        WHEN COUNT(DISTINCT s.scheme_id) > 0
                            THEN ROUND(COALESCE(SUM(w.total_water_supplied_liters), 0)::numeric / COUNT(DISTINCT s.scheme_id), 4)
                        ELSE 0::numeric
                    END AS avg_water_supply_per_scheme
                FROM child_regions c
                LEFT JOIN schemes_in_scope s
                    ON s.child_department_id = c.child_department_id
                LEFT JOIN water_by_scheme w
                    ON w.scheme_id = s.scheme_id
                GROUP BY c.child_department_id, c.title
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterSupplyMetrics(
                        null,
                        null,
                        null,
                        rs.getInt("department_id"),
                        rs.getString("title"),
                        rs.getLong("total_household_count"),
                        rs.getLong("total_fhtc_count"),
                        rs.getLong("total_planned_fhtc"),
                        rs.getLong("total_water_supplied_liters"),
                        rs.getInt("scheme_count"),
                        rs.getBigDecimal("avg_water_supply_per_scheme")),
                tenantId,
                childLevel,
                parentDepartmentId,
                tenantId,
                parentDepartmentId,
                tenantId,
                startDate,
                endDate);
    }

    public List<ChildRegionWaterQuantityMetrics> getRegionWiseWaterQuantityByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer parentLgdLevel = getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (parentLgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int childLevel = parentLgdLevel + 1;
        String parentSchemeLgdColumn = resolveSchemeLgdColumn(parentLgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(parentLgdLevel);

        String sql = String.format("""
                WITH tenant_cfg AS (
                    SELECT
                        t.tenant_id,
                        COALESCE(t.required_lpcd, 0) AS required_lpcd,
                        COALESCE(t.person_count_per_household, 5) AS person_count_per_household,
                        COALESCE(t.over_supply_range_percentage, 0) AS over_supply_range_percentage,
                        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_table t
                    JOIN analytics_schema.dim_lgd_location_table l
                        ON l.tenant_id = t.tenant_id
                    WHERE l.lgd_id = ?
                ),
                child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_lgd_id,
                        COALESCE(s.house_hold_count, 0) AS house_hold_count,
                        COALESCE(s.fhtc_count, 0) AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0) AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                ),
                dates_in_range AS (
                    SELECT d.full_date AS date
                    FROM analytics_schema.dim_date_table d
                    WHERE d.full_date BETWEEN ? AND ?
                ),
                ewater_by_scheme AS (
                    SELECT
                        f.scheme_id,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS total_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.scheme_id
                ),
                region_scheme_agg AS (
                    SELECT
                        s.child_lgd_id,
                        COALESCE(SUM(s.house_hold_count), 0)::bigint AS household_count,
                        COALESCE(SUM(s.fhtc_count), 0)::bigint AS fhtc_count,
                        COALESCE(SUM(s.planned_fhtc), 0)::bigint AS planned_fhtc,
                        COALESCE(SUM(w.total_ewater_quantity), 0)::bigint AS ewater_quantity
                    FROM schemes_in_scope s
                    LEFT JOIN ewater_by_scheme w
                        ON w.scheme_id = s.scheme_id
                    GROUP BY s.child_lgd_id
                ),
                ewater_by_scheme_day AS (
                    SELECT
                        f.scheme_id,
                        f.date,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.scheme_id, f.date
                ),
                region_supply_days AS (
                    SELECT
                        sd.child_lgd_id,
                        COALESCE(SUM(
                            CASE
                                WHEN COALESCE(wd.daily_ewater_quantity, 0)::numeric BETWEEN
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 - (tc.under_supply_range_percentage::numeric / 100))
                                     )
                                     AND
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 + (tc.over_supply_range_percentage::numeric / 100))
                                     )
                                    THEN 1
                                ELSE 0
                            END
                    ), 0)::bigint AS supply_days_in_efficient_range
                    FROM (
                        SELECT
                            s.scheme_id,
                            s.child_lgd_id,
                            s.fhtc_count,
                            dr.date
                        FROM schemes_in_scope s
                        CROSS JOIN dates_in_range dr
                    ) sd
                    LEFT JOIN ewater_by_scheme_day wd
                        ON wd.scheme_id = sd.scheme_id
                        AND wd.date = sd.date
                    CROSS JOIN tenant_cfg tc
                    GROUP BY sd.child_lgd_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(a.household_count, 0)::bigint AS household_count,
                    COALESCE(a.fhtc_count, 0)::bigint AS fhtc_count,
                    COALESCE(a.planned_fhtc, 0)::bigint AS planned_fhtc,
                    COALESCE(a.ewater_quantity, 0)::bigint AS ewater_quantity,
                    COALESCE(ps.supply_days_in_efficient_range, 0)::bigint AS supply_days_in_efficient_range
                FROM child_regions c
                LEFT JOIN region_scheme_agg a
                    ON a.child_lgd_id = c.child_lgd_id
                LEFT JOIN region_supply_days ps
                    ON ps.child_lgd_id = c.child_lgd_id
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterQuantityMetrics(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("title"),
                        rs.getLong("ewater_quantity"),
                        rs.getLong("household_count"),
                        rs.getLong("fhtc_count"),
                        rs.getLong("planned_fhtc"),
                        rs.getLong("supply_days_in_efficient_range")),
                parentLgdId,
                childLevel,
                parentLgdId,
                parentLgdId,
                startDate,
                endDate,
                startDate,
                endDate,
                startDate,
                endDate);
    }

    public List<ChildRegionWaterQuantityMetrics> getRegionWiseWaterQuantityByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        Integer parentLgdLevel = getLgdLevelForTenant(tenantId, parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (parentLgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int childLevel = parentLgdLevel + 1;
        String parentSchemeLgdColumn = resolveSchemeLgdColumn(parentLgdLevel);
        String childSchemeLgdColumn = resolveSchemeLgdColumn(childLevel);
        String childRegionParentLgdColumn = resolveChildRegionLgdParentColumn(parentLgdLevel);

        String sql = String.format("""
                WITH tenant_cfg AS (
                    SELECT
                        t.tenant_id,
                        COALESCE(t.required_lpcd, 0) AS required_lpcd,
                        COALESCE(t.person_count_per_household, 5) AS person_count_per_household,
                        COALESCE(t.over_supply_range_percentage, 0) AS over_supply_range_percentage,
                        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_table t
                    WHERE t.tenant_id = ?
                ),
                child_regions AS (
                    SELECT
                        l.lgd_id AS child_lgd_id,
                        l.title
                    FROM analytics_schema.dim_lgd_location_table l
                    WHERE l.lgd_level = ?
                      AND l.%1$s = ?
                      AND l.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id, s.%2$s)
                        s.scheme_id,
                        s.%2$s AS child_lgd_id,
                        COALESCE(s.house_hold_count, 0) AS house_hold_count,
                        COALESCE(s.fhtc_count, 0) AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0) AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                      AND s.tenant_id = ?
                    ORDER BY s.scheme_id, s.%2$s, COALESCE(s.fhtc_count, 0) DESC, COALESCE(s.house_hold_count, 0) DESC, COALESCE(s.planned_fhtc, 0) DESC
                ),
                dates_in_range AS (
                    SELECT d.full_date AS date
                    FROM analytics_schema.dim_date_table d
                    WHERE d.full_date BETWEEN ? AND ?
                ),
                ewater_by_scheme AS (
                    SELECT
                        f.scheme_id,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS total_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                      AND f.tenant_id = ?
                    GROUP BY f.scheme_id
                ),
                region_scheme_agg AS (
                    SELECT
                        s.child_lgd_id,
                        COALESCE(SUM(s.house_hold_count), 0)::bigint AS household_count,
                        COALESCE(SUM(s.fhtc_count), 0)::bigint AS fhtc_count,
                        COALESCE(SUM(s.planned_fhtc), 0)::bigint AS planned_fhtc,
                        COALESCE(SUM(w.total_ewater_quantity), 0)::bigint AS ewater_quantity
                    FROM schemes_in_scope s
                    LEFT JOIN ewater_by_scheme w
                        ON w.scheme_id = s.scheme_id
                    GROUP BY s.child_lgd_id
                ),
                ewater_by_scheme_day AS (
                    SELECT
                        f.scheme_id,
                        f.date,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                      AND f.tenant_id = ?
                    GROUP BY f.scheme_id, f.date
                ),
                region_supply_days AS (
                    SELECT
                        sd.child_lgd_id,
                        COALESCE(SUM(
                            CASE
                                WHEN COALESCE(wd.daily_ewater_quantity, 0)::numeric BETWEEN
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 - (tc.under_supply_range_percentage::numeric / 100))
                                     )
                                     AND
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 + (tc.over_supply_range_percentage::numeric / 100))
                                     )
                                    THEN 1
                                ELSE 0
                            END
                    ), 0)::bigint AS supply_days_in_efficient_range
                    FROM (
                        SELECT
                            s.scheme_id,
                            s.child_lgd_id,
                            s.fhtc_count,
                            dr.date
                        FROM schemes_in_scope s
                        CROSS JOIN dates_in_range dr
                    ) sd
                    LEFT JOIN ewater_by_scheme_day wd
                        ON wd.scheme_id = sd.scheme_id
                        AND wd.date = sd.date
                    CROSS JOIN tenant_cfg tc
                    GROUP BY sd.child_lgd_id
                )
                SELECT
                    c.child_lgd_id AS lgd_id,
                    c.title,
                    COALESCE(a.household_count, 0)::bigint AS household_count,
                    COALESCE(a.fhtc_count, 0)::bigint AS fhtc_count,
                    COALESCE(a.planned_fhtc, 0)::bigint AS planned_fhtc,
                    COALESCE(a.ewater_quantity, 0)::bigint AS ewater_quantity,
                    COALESCE(ps.supply_days_in_efficient_range, 0)::bigint AS supply_days_in_efficient_range
                FROM child_regions c
                LEFT JOIN region_scheme_agg a
                    ON a.child_lgd_id = c.child_lgd_id
                LEFT JOIN region_supply_days ps
                    ON ps.child_lgd_id = c.child_lgd_id
                ORDER BY c.child_lgd_id
                """, childRegionParentLgdColumn, childSchemeLgdColumn, parentSchemeLgdColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterQuantityMetrics(
                        rs.getInt("lgd_id"),
                        null,
                        rs.getString("title"),
                        rs.getLong("ewater_quantity"),
                        rs.getLong("household_count"),
                        rs.getLong("fhtc_count"),
                        rs.getLong("planned_fhtc"),
                        rs.getLong("supply_days_in_efficient_range")),
                tenantId,
                childLevel,
                parentLgdId,
                tenantId,
                parentLgdId,
                tenantId,
                startDate,
                endDate,
                startDate,
                endDate,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionWaterQuantityMetrics> getRegionWiseWaterQuantityByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer parentDepartmentLevel = getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (parentDepartmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int childLevel = parentDepartmentLevel + 1;
        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(parentDepartmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(parentDepartmentLevel);

        String sql = String.format("""
                WITH tenant_cfg AS (
                    SELECT
                        t.tenant_id,
                        COALESCE(t.required_lpcd, 0) AS required_lpcd,
                        COALESCE(t.person_count_per_household, 5) AS person_count_per_household,
                        COALESCE(t.over_supply_range_percentage, 0) AS over_supply_range_percentage,
                        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_table t
                    JOIN analytics_schema.dim_department_location_table d
                        ON d.tenant_id = t.tenant_id
                    WHERE d.department_id = ?
                ),
                child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.scheme_id,
                        s.%2$s AS child_department_id,
                        COALESCE(s.house_hold_count, 0) AS house_hold_count,
                        COALESCE(s.fhtc_count, 0) AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0) AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                ),
                dates_in_range AS (
                    SELECT d.full_date AS date
                    FROM analytics_schema.dim_date_table d
                    WHERE d.full_date BETWEEN ? AND ?
                ),
                ewater_by_scheme AS (
                    SELECT
                        f.scheme_id,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS total_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.scheme_id
                ),
                region_scheme_agg AS (
                    SELECT
                        s.child_department_id,
                        COALESCE(SUM(s.house_hold_count), 0)::bigint AS household_count,
                        COALESCE(SUM(s.fhtc_count), 0)::bigint AS fhtc_count,
                        COALESCE(SUM(s.planned_fhtc), 0)::bigint AS planned_fhtc,
                        COALESCE(SUM(w.total_ewater_quantity), 0)::bigint AS ewater_quantity
                    FROM schemes_in_scope s
                    LEFT JOIN ewater_by_scheme w
                        ON w.scheme_id = s.scheme_id
                    GROUP BY s.child_department_id
                ),
                ewater_by_scheme_day AS (
                    SELECT
                        f.scheme_id,
                        f.date,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY f.scheme_id, f.date
                ),
                region_supply_days AS (
                    SELECT
                        sd.child_department_id,
                        COALESCE(SUM(
                            CASE
                                WHEN COALESCE(wd.daily_ewater_quantity, 0)::numeric BETWEEN
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 - (tc.under_supply_range_percentage::numeric / 100))
                                     )
                                     AND
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 + (tc.over_supply_range_percentage::numeric / 100))
                                     )
                                    THEN 1
                                ELSE 0
                            END
                    ), 0)::bigint AS supply_days_in_efficient_range
                    FROM (
                        SELECT
                            s.scheme_id,
                            s.child_department_id,
                            s.fhtc_count,
                            dr.date
                        FROM schemes_in_scope s
                        CROSS JOIN dates_in_range dr
                    ) sd
                    LEFT JOIN ewater_by_scheme_day wd
                        ON wd.scheme_id = sd.scheme_id
                        AND wd.date = sd.date
                    CROSS JOIN tenant_cfg tc
                    GROUP BY sd.child_department_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(a.household_count, 0)::bigint AS household_count,
                    COALESCE(a.fhtc_count, 0)::bigint AS fhtc_count,
                    COALESCE(a.planned_fhtc, 0)::bigint AS planned_fhtc,
                    COALESCE(a.ewater_quantity, 0)::bigint AS ewater_quantity,
                    COALESCE(ps.supply_days_in_efficient_range, 0)::bigint AS supply_days_in_efficient_range
                FROM child_regions c
                LEFT JOIN region_scheme_agg a
                    ON a.child_department_id = c.child_department_id
                LEFT JOIN region_supply_days ps
                    ON ps.child_department_id = c.child_department_id
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterQuantityMetrics(
                        null,
                        rs.getInt("department_id"),
                        rs.getString("title"),
                        rs.getLong("ewater_quantity"),
                        rs.getLong("household_count"),
                        rs.getLong("fhtc_count"),
                        rs.getLong("planned_fhtc"),
                        rs.getLong("supply_days_in_efficient_range")),
                parentDepartmentId,
                childLevel,
                parentDepartmentId,
                parentDepartmentId,
                startDate,
                endDate,
                startDate,
                endDate,
                startDate,
                endDate);
    }

    public List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityByLgdId(
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        return getPeriodicSchemeRegularityMetrics(schemeLgdColumn, lgdId, startDate, endDate, scale);
    }

    public List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        return getPeriodicSchemeRegularityMetrics(
                schemeDepartmentColumn, departmentId, startDate, endDate, scale);
    }

    public List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        return getPeriodicSchemeRegularityMetricsForTenant(tenantId, schemeLgdColumn, lgdId, startDate, endDate, scale);
    }

    public List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        return getPeriodicSchemeRegularityMetricsForTenant(
                tenantId, schemeDepartmentColumn, departmentId, startDate, endDate, scale);
    }

    public List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityForNation(
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        PeriodSqlParts sqlParts = buildPeriodSqlPartsForMeterReadings(scale);

        String sql = String.format("""
                WITH params AS (
                    SELECT ?::date AS anchor_start
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT
                        s.tenant_id,
                        s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                ),
                scheme_fhtc_totals AS (
                    SELECT
                        COALESCE(SUM(s.fhtc_count), 0)::bigint AS total_achieved_fhtc_count
                    FROM (
                        SELECT DISTINCT ON (tenant_id, scheme_id) tenant_id, scheme_id, COALESCE(fhtc_count, 0) AS fhtc_count
                        FROM analytics_schema.dim_scheme_table
                        ORDER BY tenant_id, scheme_id, COALESCE(fhtc_count, 0) DESC
                    ) s
                ),
                periods AS (
                    SELECT DISTINCT
                        %1$s AS period_start_date,
                        %2$s AS period_end_date,
                        %3$s AS scope
                    FROM params,
                         generate_series(?::date, ?::date, INTERVAL '1 day') AS g(day_date)
                ),
                scheme_supply_days AS (
                    SELECT
                        m.tenant_id,
                        m.scheme_id,
                        %4$s AS period_start_date,
                        COUNT(DISTINCT m.reading_date)::int AS supply_days,
                        COALESCE(SUM(m.confirmed_reading), 0)::bigint AS total_water_quantity
                    FROM params,
                         analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope s
                        ON s.scheme_id = m.scheme_id
                        AND s.tenant_id = m.tenant_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.confirmed_reading > 0
                    GROUP BY m.tenant_id, m.scheme_id, %4$s
                ),
                period_supply AS (
                    SELECT
                        period_start_date,
                        COALESCE(SUM(supply_days)::int, 0) AS total_supply_days,
                        COALESCE(SUM(total_water_quantity)::bigint, 0) AS total_water_quantity
                    FROM scheme_supply_days
                    GROUP BY period_start_date
                )
                SELECT
                    p.period_start_date,
                    p.period_end_date,
                    COALESCE((SELECT COUNT(*)::int FROM schemes_in_scope), 0) AS scheme_count,
                    COALESCE((SELECT total_achieved_fhtc_count FROM scheme_fhtc_totals), 0)::bigint AS total_achieved_fhtc_count,
                    COALESCE(ps.total_supply_days, 0) AS total_supply_days,
                    COALESCE(ps.total_water_quantity, 0)::bigint AS total_water_quantity
                FROM periods p
                LEFT JOIN period_supply ps
                    ON ps.period_start_date = p.period_start_date
                ORDER BY p.period_start_date
                """,
                sqlParts.periodStartFromSeries(),
                sqlParts.periodEndFromSeries(),
                sqlParts.periodLabelFromSeries(),
                sqlParts.periodStartFromFact());

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PeriodicSchemeRegularityMetrics(
                        rs.getObject("period_start_date", LocalDate.class),
                        rs.getObject("period_end_date", LocalDate.class),
                        rs.getInt("scheme_count"),
                        rs.getLong("total_achieved_fhtc_count"),
                        rs.getInt("total_supply_days"),
                        rs.getLong("total_water_quantity")),
                startDate,
                startDate,
                endDate,
                startDate,
                endDate);
    }

    private List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityMetrics(
            String schemeLocationColumn,
            Object locationId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale) {
        PeriodSqlParts sqlParts = buildPeriodSqlPartsForSchemeDay(scale);
        String sql = String.format("""
                WITH params AS (
                    SELECT ?::date AS anchor_start
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id)
                        s.scheme_id,
                        COALESCE(s.fhtc_count, 0)::bigint AS achieved_fhtc_count
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                    ORDER BY s.scheme_id, COALESCE(s.fhtc_count, 0) DESC
                ),
                scheme_fhtc_totals AS (
                    SELECT
                        COALESCE(SUM(s.achieved_fhtc_count), 0)::bigint AS total_achieved_fhtc_count
                    FROM schemes_in_scope s
                ),
                periods AS (
                    SELECT DISTINCT
                        %2$s AS period_start_date,
                        %3$s AS period_end_date,
                        %4$s AS scope
                    FROM params,
                         generate_series(?::date, ?::date, INTERVAL '1 day') AS g(day_date)
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint AS day_water_quantity
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope s
                        ON s.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_supply_days AS (
                    SELECT
                        sd.scheme_id,
                        %5$s AS period_start_date,
                        COUNT(*) FILTER (WHERE sd.day_water_quantity > 0)::int AS supply_days,
                        COALESCE(SUM(sd.day_water_quantity), 0)::bigint AS total_water_quantity
                    FROM params,
                         scheme_day sd
                    GROUP BY sd.scheme_id, %5$s
                ),
                period_supply AS (
                    SELECT
                        period_start_date,
                        COALESCE(SUM(supply_days)::int, 0) AS total_supply_days,
                        COALESCE(SUM(total_water_quantity)::bigint, 0) AS total_water_quantity
                    FROM scheme_supply_days
                    GROUP BY period_start_date
                )
                SELECT
                    p.period_start_date,
                    p.period_end_date,
                    COALESCE((SELECT COUNT(*)::int FROM schemes_in_scope), 0) AS scheme_count,
                    COALESCE((SELECT total_achieved_fhtc_count FROM scheme_fhtc_totals), 0)::bigint AS total_achieved_fhtc_count,
                    COALESCE(ps.total_supply_days, 0) AS total_supply_days,
                    COALESCE(ps.total_water_quantity, 0)::bigint AS total_water_quantity
                FROM periods p
                LEFT JOIN period_supply ps
                    ON ps.period_start_date = p.period_start_date
                ORDER BY p.period_start_date
                """,
                schemeLocationColumn,
                sqlParts.periodStartFromSeries(),
                sqlParts.periodEndFromSeries(),
                sqlParts.periodLabelFromSeries(),
                sqlParts.periodStartFromFact());

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PeriodicSchemeRegularityMetrics(
                        rs.getObject("period_start_date", LocalDate.class),
                        rs.getObject("period_end_date", LocalDate.class),
                        rs.getInt("scheme_count"),
                        rs.getLong("total_achieved_fhtc_count"),
                        rs.getInt("total_supply_days"),
                        rs.getLong("total_water_quantity")),
                startDate,
                locationId,
                startDate,
                endDate,
                startDate,
                endDate);
    }

    private List<PeriodicSchemeRegularityMetrics> getPeriodicSchemeRegularityMetricsForTenant(
            Integer tenantId,
            String schemeLocationColumn,
            Object locationId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale) {
        PeriodSqlParts sqlParts = buildPeriodSqlPartsForSchemeDay(scale);
        String sql = String.format("""
                WITH params AS (
                    SELECT ?::date AS anchor_start
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id)
                        s.scheme_id,
                        COALESCE(s.fhtc_count, 0)::bigint AS achieved_fhtc_count
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                    ORDER BY s.scheme_id, COALESCE(s.fhtc_count, 0) DESC
                ),
                scheme_fhtc_totals AS (
                    SELECT
                        COALESCE(SUM(s.achieved_fhtc_count), 0)::bigint AS total_achieved_fhtc_count
                    FROM schemes_in_scope s
                ),
                periods AS (
                    SELECT DISTINCT
                        %2$s AS period_start_date,
                        %3$s AS period_end_date,
                        %4$s AS scope
                    FROM params,
                         generate_series(?::date, ?::date, INTERVAL '1 day') AS g(day_date)
                ),
                scheme_day AS (
                    SELECT
                        m.scheme_id,
                        m.reading_date::date AS reading_date,
                        COALESCE(SUM(CASE WHEN m.confirmed_reading > 0 THEN m.confirmed_reading ELSE 0 END), 0)::bigint AS day_water_quantity
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN schemes_in_scope s
                        ON s.scheme_id = m.scheme_id
                    WHERE m.reading_date BETWEEN ? AND ?
                      AND m.tenant_id = ?
                    GROUP BY m.scheme_id, m.reading_date::date
                ),
                scheme_supply_days AS (
                    SELECT
                        sd.scheme_id,
                        %5$s AS period_start_date,
                        COUNT(*) FILTER (WHERE sd.day_water_quantity > 0)::int AS supply_days,
                        COALESCE(SUM(sd.day_water_quantity), 0)::bigint AS total_water_quantity
                    FROM params,
                         scheme_day sd
                    GROUP BY sd.scheme_id, %5$s
                ),
                period_supply AS (
                    SELECT
                        period_start_date,
                        COALESCE(SUM(supply_days)::int, 0) AS total_supply_days,
                        COALESCE(SUM(total_water_quantity)::bigint, 0) AS total_water_quantity
                    FROM scheme_supply_days
                    GROUP BY period_start_date
                )
                SELECT
                    p.period_start_date,
                    p.period_end_date,
                    COALESCE((SELECT COUNT(*)::int FROM schemes_in_scope), 0) AS scheme_count,
                    COALESCE((SELECT total_achieved_fhtc_count FROM scheme_fhtc_totals), 0)::bigint AS total_achieved_fhtc_count,
                    COALESCE(ps.total_supply_days, 0) AS total_supply_days,
                    COALESCE(ps.total_water_quantity, 0)::bigint AS total_water_quantity
                FROM periods p
                LEFT JOIN period_supply ps
                    ON ps.period_start_date = p.period_start_date
                ORDER BY p.period_start_date
                """,
                schemeLocationColumn,
                sqlParts.periodStartFromSeries(),
                sqlParts.periodEndFromSeries(),
                sqlParts.periodLabelFromSeries(),
                sqlParts.periodStartFromFact());

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PeriodicSchemeRegularityMetrics(
                        rs.getObject("period_start_date", LocalDate.class),
                        rs.getObject("period_end_date", LocalDate.class),
                        rs.getInt("scheme_count"),
                        rs.getLong("total_achieved_fhtc_count"),
                        rs.getInt("total_supply_days"),
                        rs.getLong("total_water_quantity")),
                startDate,
                locationId,
                tenantId,
                startDate,
                endDate,
                startDate,
                endDate,
                tenantId);
    }

    public List<ChildRegionWaterQuantityMetrics> getRegionWiseWaterQuantityByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        Integer parentDepartmentLevel = getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (parentDepartmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int childLevel = parentDepartmentLevel + 1;
        String parentSchemeDepartmentColumn = resolveSchemeDepartmentColumn(parentDepartmentLevel);
        String childSchemeDepartmentColumn = resolveSchemeDepartmentColumn(childLevel);
        String childRegionParentDepartmentColumn = resolveChildRegionDepartmentParentColumn(parentDepartmentLevel);

        String sql = String.format("""
                WITH tenant_cfg AS (
                    SELECT
                        t.tenant_id,
                        COALESCE(t.required_lpcd, 0) AS required_lpcd,
                        COALESCE(t.person_count_per_household, 5) AS person_count_per_household,
                        COALESCE(t.over_supply_range_percentage, 0) AS over_supply_range_percentage,
                        COALESCE(t.under_supply_range_percentage, 0) AS under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_table t
                    WHERE t.tenant_id = ?
                ),
                child_regions AS (
                    SELECT
                        d.department_id AS child_department_id,
                        d.title
                    FROM analytics_schema.dim_department_location_table d
                    WHERE d.department_level = ?
                      AND d.%1$s = ?
                      AND d.tenant_id = ?
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id, s.%2$s)
                        s.scheme_id,
                        s.%2$s AS child_department_id,
                        COALESCE(s.house_hold_count, 0) AS house_hold_count,
                        COALESCE(s.fhtc_count, 0) AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0) AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%3$s = ?
                      AND s.tenant_id = ?
                    ORDER BY s.scheme_id, s.%2$s, COALESCE(s.fhtc_count, 0) DESC, COALESCE(s.house_hold_count, 0) DESC, COALESCE(s.planned_fhtc, 0) DESC
                ),
                dates_in_range AS (
                    SELECT d.full_date AS date
                    FROM analytics_schema.dim_date_table d
                    WHERE d.full_date BETWEEN ? AND ?
                ),
                ewater_by_scheme AS (
                    SELECT
                        f.scheme_id,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS total_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                      AND f.tenant_id = ?
                    GROUP BY f.scheme_id
                ),
                region_scheme_agg AS (
                    SELECT
                        s.child_department_id,
                        COALESCE(SUM(s.house_hold_count), 0)::bigint AS household_count,
                        COALESCE(SUM(s.fhtc_count), 0)::bigint AS fhtc_count,
                        COALESCE(SUM(s.planned_fhtc), 0)::bigint AS planned_fhtc,
                        COALESCE(SUM(w.total_ewater_quantity), 0)::bigint AS ewater_quantity
                    FROM schemes_in_scope s
                    LEFT JOIN ewater_by_scheme w
                        ON w.scheme_id = s.scheme_id
                    GROUP BY s.child_department_id
                ),
                ewater_by_scheme_day AS (
                    SELECT
                        f.scheme_id,
                        f.date,
                        COALESCE(SUM(f.water_quantity), 0)::bigint AS daily_ewater_quantity
                    FROM analytics_schema.fact_water_quantity_table f
                    WHERE f.date BETWEEN ? AND ?
                      AND f.tenant_id = ?
                    GROUP BY f.scheme_id, f.date
                ),
                region_supply_days AS (
                    SELECT
                        sd.child_department_id,
                        COALESCE(SUM(
                            CASE
                                WHEN COALESCE(wd.daily_ewater_quantity, 0)::numeric BETWEEN
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 - (tc.under_supply_range_percentage::numeric / 100))
                                     )
                                     AND
                                     (
                                         (tc.required_lpcd::numeric * (sd.fhtc_count::numeric * tc.person_count_per_household::numeric))
                                         * (1 + (tc.over_supply_range_percentage::numeric / 100))
                                     )
                                    THEN 1
                                ELSE 0
                            END
                    ), 0)::bigint AS supply_days_in_efficient_range
                    FROM (
                        SELECT
                            s.scheme_id,
                            s.child_department_id,
                            s.fhtc_count,
                            dr.date
                        FROM schemes_in_scope s
                        CROSS JOIN dates_in_range dr
                    ) sd
                    LEFT JOIN ewater_by_scheme_day wd
                        ON wd.scheme_id = sd.scheme_id
                        AND wd.date = sd.date
                    CROSS JOIN tenant_cfg tc
                    GROUP BY sd.child_department_id
                )
                SELECT
                    c.child_department_id AS department_id,
                    c.title,
                    COALESCE(a.household_count, 0)::bigint AS household_count,
                    COALESCE(a.fhtc_count, 0)::bigint AS fhtc_count,
                    COALESCE(a.planned_fhtc, 0)::bigint AS planned_fhtc,
                    COALESCE(a.ewater_quantity, 0)::bigint AS ewater_quantity,
                    COALESCE(ps.supply_days_in_efficient_range, 0)::bigint AS supply_days_in_efficient_range
                FROM child_regions c
                LEFT JOIN region_scheme_agg a
                    ON a.child_department_id = c.child_department_id
                LEFT JOIN region_supply_days ps
                    ON ps.child_department_id = c.child_department_id
                ORDER BY c.child_department_id
                """, childRegionParentDepartmentColumn, childSchemeDepartmentColumn, parentSchemeDepartmentColumn);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ChildRegionWaterQuantityMetrics(
                        null,
                        rs.getInt("department_id"),
                        rs.getString("title"),
                        rs.getLong("ewater_quantity"),
                        rs.getLong("household_count"),
                        rs.getLong("fhtc_count"),
                        rs.getLong("planned_fhtc"),
                        rs.getLong("supply_days_in_efficient_range")),
                tenantId,
                childLevel,
                parentDepartmentId,
                tenantId,
                parentDepartmentId,
                tenantId,
                startDate,
                endDate,
                startDate,
                endDate,
                tenantId,
                startDate,
                endDate,
                tenantId);
    }

    public List<PeriodicWaterQuantityMetrics> getPeriodicWaterQuantityByLgdId(
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        return getPeriodicWaterQuantityMetrics(schemeLgdColumn, lgdId, startDate, endDate, scale);
    }

    public List<PeriodicWaterQuantityMetrics> getPeriodicWaterQuantityByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        return getPeriodicWaterQuantityMetrics(schemeDepartmentColumn, departmentId, startDate, endDate, scale);
    }

    public List<PeriodicOutageReasonSchemeCountRow> getPeriodicOutageReasonSchemeCountByLgdId(
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer lgdLevel = getLgdLevel(lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        return getPeriodicOutageReasonSchemeCountRows(schemeLgdColumn, lgdId, startDate, endDate, scale);
    }

    public List<PeriodicOutageReasonSchemeCountRow> getPeriodicOutageReasonSchemeCountByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer lgdLevel = getLgdLevelForTenant(tenantId, lgdId);
        if (lgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }
        String schemeLgdColumn = resolveSchemeLgdColumn(lgdLevel);
        return getPeriodicOutageReasonSchemeCountRowsForTenant(
                schemeLgdColumn, lgdId, tenantId, startDate, endDate, scale);
    }

    public List<PeriodicOutageReasonSchemeCountRow> getPeriodicOutageReasonSchemeCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer departmentLevel = getDepartmentLevel(departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        return getPeriodicOutageReasonSchemeCountRows(
                schemeDepartmentColumn, departmentId, startDate, endDate, scale);
    }

    public List<PeriodicOutageReasonSchemeCountRow> getPeriodicOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        Integer departmentLevel = getDepartmentLevelForTenant(tenantId, departmentId);
        if (departmentLevel == null) {
            throw new IllegalArgumentException("department_id not found in dim_department_location_table: " + departmentId);
        }
        String schemeDepartmentColumn = resolveSchemeDepartmentColumn(departmentLevel);
        return getPeriodicOutageReasonSchemeCountRowsForTenant(
                schemeDepartmentColumn, departmentId, tenantId, startDate, endDate, scale);
    }

    private List<PeriodicOutageReasonSchemeCountRow> getPeriodicOutageReasonSchemeCountRows(
            String schemeLocationColumn,
            Object locationId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale) {
        PeriodSqlParts sqlParts = buildPeriodSqlParts(scale);
        String sql = String.format("""
                WITH params AS (
                    SELECT ?::date AS anchor_start
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                ),
                periods AS (
                    SELECT DISTINCT
                        %2$s AS period_start_date,
                        %3$s AS period_end_date,
                        %4$s AS scope
                    FROM params,
                         generate_series(?::date, ?::date, INTERVAL '1 day') AS g(day_date)
                ),
                outage_by_period AS (
                    SELECT
                        %5$s AS period_start_date,
                        f.outage_reason,
                        COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                    FROM params,
                         analytics_schema.fact_water_quantity_table f
                    JOIN schemes_in_scope s
                        ON s.scheme_id = f.scheme_id
                    WHERE f.outage_reason IS NOT NULL
                      AND f.date BETWEEN ? AND ?
                    GROUP BY %5$s, f.outage_reason
                )
                SELECT
                    p.period_start_date,
                    p.period_end_date,
                    o.outage_reason,
                    o.scheme_count
                FROM periods p
                LEFT JOIN outage_by_period o
                    ON o.period_start_date = p.period_start_date
                ORDER BY p.period_start_date, o.outage_reason
                """,
                schemeLocationColumn,
                sqlParts.periodStartFromSeries(),
                sqlParts.periodEndFromSeries(),
                sqlParts.periodLabelFromSeries(),
                sqlParts.periodStartFromFact());

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    String reason = rs.getString("outage_reason");
                    Integer count = null;
                    if (reason != null) {
                        count = rs.getInt("scheme_count");
                    }
                    return new PeriodicOutageReasonSchemeCountRow(
                            rs.getObject("period_start_date", LocalDate.class),
                            rs.getObject("period_end_date", LocalDate.class),
                            reason,
                            count);
                },
                startDate,
                locationId,
                startDate,
                endDate,
                startDate,
                endDate);
    }

    private List<PeriodicOutageReasonSchemeCountRow> getPeriodicOutageReasonSchemeCountRowsForTenant(
            String schemeLocationColumn,
            Object locationId,
            Integer tenantId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale) {
        PeriodSqlParts sqlParts = buildPeriodSqlParts(scale);
        String sql = String.format("""
                WITH params AS (
                    SELECT ?::date AS anchor_start
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT s.scheme_id
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                      AND s.tenant_id = ?
                ),
                periods AS (
                    SELECT DISTINCT
                        %2$s AS period_start_date,
                        %3$s AS period_end_date,
                        %4$s AS scope
                    FROM params,
                         generate_series(?::date, ?::date, INTERVAL '1 day') AS g(day_date)
                ),
                outage_by_period AS (
                    SELECT
                        %5$s AS period_start_date,
                        f.outage_reason,
                        COUNT(DISTINCT f.scheme_id)::int AS scheme_count
                    FROM params,
                         analytics_schema.fact_water_quantity_table f
                    JOIN schemes_in_scope s
                        ON s.scheme_id = f.scheme_id
                    WHERE f.outage_reason IS NOT NULL
                      AND f.date BETWEEN ? AND ?
                      AND f.tenant_id = ?
                    GROUP BY %5$s, f.outage_reason
                )
                SELECT
                    p.period_start_date,
                    p.period_end_date,
                    o.outage_reason,
                    o.scheme_count
                FROM periods p
                LEFT JOIN outage_by_period o
                    ON o.period_start_date = p.period_start_date
                ORDER BY p.period_start_date, o.outage_reason
                """,
                schemeLocationColumn,
                sqlParts.periodStartFromSeries(),
                sqlParts.periodEndFromSeries(),
                sqlParts.periodLabelFromSeries(),
                sqlParts.periodStartFromFact());

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    String reason = rs.getString("outage_reason");
                    Integer count = null;
                    if (reason != null) {
                        count = rs.getInt("scheme_count");
                    }
                    return new PeriodicOutageReasonSchemeCountRow(
                            rs.getObject("period_start_date", LocalDate.class),
                            rs.getObject("period_end_date", LocalDate.class),
                            reason,
                            count);
                },
                startDate,
                locationId,
                tenantId,
                startDate,
                endDate,
                startDate,
                endDate,
                tenantId);
    }

    private List<PeriodicWaterQuantityMetrics> getPeriodicWaterQuantityMetrics(
            String schemeLocationColumn,
            Object locationId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale) {
        PeriodSqlParts sqlParts = buildPeriodSqlParts(scale);
        String sql = String.format("""
                WITH params AS (
                    SELECT ?::date AS anchor_start
                ),
                schemes_in_scope AS (
                    SELECT DISTINCT ON (s.scheme_id)
                        s.scheme_id,
                        COALESCE(s.house_hold_count, 0)::bigint AS house_hold_count,
                        COALESCE(s.fhtc_count, 0)::bigint AS fhtc_count,
                        COALESCE(s.planned_fhtc, 0)::bigint AS planned_fhtc
                    FROM analytics_schema.dim_scheme_table s
                    WHERE s.%1$s = ?
                    ORDER BY s.scheme_id, COALESCE(s.fhtc_count, 0) DESC, COALESCE(s.house_hold_count, 0) DESC, COALESCE(s.planned_fhtc, 0) DESC
                ),
                periods AS (
                    SELECT DISTINCT
                        %2$s AS period_start_date,
                        %3$s AS period_end_date,
                        %4$s AS scope
                    FROM params,
                         generate_series(?::date, ?::date, INTERVAL '1 day') AS g(day_date)
                ),
                water_by_period AS (
                    SELECT
                        %5$s AS period_start_date,
                        AVG(f.water_quantity::numeric) AS avg_water_quantity
                    FROM params,
                         analytics_schema.fact_water_quantity_table f
                    JOIN schemes_in_scope s
                        ON s.scheme_id = f.scheme_id
                    WHERE f.date BETWEEN ? AND ?
                    GROUP BY %5$s
                ),
                household_total AS (
                    SELECT
                        COALESCE(SUM(house_hold_count), 0)::bigint AS household_count,
                        COALESCE(SUM(fhtc_count), 0)::bigint AS fhtc_count,
                        COALESCE(SUM(planned_fhtc), 0)::bigint AS planned_fhtc
                    FROM schemes_in_scope
                )
                SELECT
                    p.period_start_date,
                    p.period_end_date,
                    p.scope,
                    COALESCE(w.avg_water_quantity, 0)::numeric AS average_water_quantity,
                    h.household_count,
                    h.fhtc_count,
                    h.planned_fhtc
                FROM periods p
                LEFT JOIN water_by_period w
                    ON w.period_start_date = p.period_start_date
                CROSS JOIN household_total h
                ORDER BY p.period_start_date
                """,
                schemeLocationColumn,
                sqlParts.periodStartFromSeries(),
                sqlParts.periodEndFromSeries(),
                sqlParts.periodLabelFromSeries(),
                sqlParts.periodStartFromFact());

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PeriodicWaterQuantityMetrics(
                        rs.getObject("period_start_date", LocalDate.class),
                        rs.getObject("period_end_date", LocalDate.class),
                        rs.getString("scope"),
                        rs.getBigDecimal("average_water_quantity").setScale(4, RoundingMode.HALF_UP),
                        rs.getLong("household_count"),
                        rs.getLong("fhtc_count"),
                        rs.getLong("planned_fhtc")),
                startDate,
                locationId,
                startDate,
                endDate,
                startDate,
                endDate);
    }

    public Integer getLgdLevel(Integer lgdId) {
        String sql = """
                SELECT l.lgd_level
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.lgd_id = ?
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> (Integer) rs.getObject("lgd_level"), lgdId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public Integer getLgdLevelForTenant(Integer tenantId, Integer lgdId) {
        String sql = """
                SELECT l.lgd_level
                FROM analytics_schema.dim_lgd_location_table l
                WHERE l.lgd_id = ?
                  AND l.tenant_id = ?
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> (Integer) rs.getObject("lgd_level"), lgdId, tenantId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private PeriodSqlParts buildPeriodSqlParts(PeriodScale scale) {
        // Period alignment rules:
        // - WEEK: rolling 7-day buckets anchored to the request start_date (params.anchor_start), not ISO-week aligned.
        // - MONTH/QUARTER/YEAR: calendar-aligned buckets via DATE_TRUNC (month=Jan/Feb..., quarter=Jan-Mar/Apr-Jun..., year=Jan 1-Dec 31).
        // These fragments assume the calling query defines `params(anchor_start)` CTE when WEEK scale is used.
        return switch (scale) {
            case DAY -> new PeriodSqlParts(
                    "g.day_date::date",
                    "g.day_date::date",
                    "TO_CHAR(g.day_date::date, 'YYYY-MM-DD')",
                    "f.date::date");
            case WEEK -> new PeriodSqlParts(
                    "(params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7))::date",
                    "(params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7) + 6)::date",
                    "TO_CHAR((params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7))::date, 'YYYY-MM-DD')",
                    "(params.anchor_start + (((f.date::date - params.anchor_start) / 7) * 7))::date");
            case MONTH -> new PeriodSqlParts(
                    "DATE_TRUNC('month', g.day_date)::date",
                    "(DATE_TRUNC('month', g.day_date)::date + INTERVAL '1 month - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('month', g.day_date)::date, 'YYYY-MM')",
                    "DATE_TRUNC('month', f.date)::date");
            case QUARTER -> new PeriodSqlParts(
                    "DATE_TRUNC('quarter', g.day_date)::date",
                    "(DATE_TRUNC('quarter', g.day_date)::date + INTERVAL '3 month - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('quarter', g.day_date)::date, 'YYYY-\"Q\"Q')",
                    "DATE_TRUNC('quarter', f.date)::date");
            case YEAR -> new PeriodSqlParts(
                    "DATE_TRUNC('year', g.day_date)::date",
                    "(DATE_TRUNC('year', g.day_date)::date + INTERVAL '1 year - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('year', g.day_date)::date, 'YYYY')",
                    "DATE_TRUNC('year', f.date)::date");
        };
    }

    private PeriodSqlParts buildPeriodSqlPartsForMeterReadings(PeriodScale scale) {
        // Same alignment rules as buildPeriodSqlParts(), but fact date column is m.reading_date.
        return switch (scale) {
            case DAY -> new PeriodSqlParts(
                    "g.day_date::date",
                    "g.day_date::date",
                    "TO_CHAR(g.day_date::date, 'YYYY-MM-DD')",
                    "m.reading_date::date");
            case WEEK -> new PeriodSqlParts(
                    "(params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7))::date",
                    "(params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7) + 6)::date",
                    "TO_CHAR((params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7))::date, 'YYYY-MM-DD')",
                    "(params.anchor_start + (((m.reading_date::date - params.anchor_start) / 7) * 7))::date");
            case MONTH -> new PeriodSqlParts(
                    "DATE_TRUNC('month', g.day_date)::date",
                    "(DATE_TRUNC('month', g.day_date)::date + INTERVAL '1 month - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('month', g.day_date)::date, 'YYYY-MM')",
                    "DATE_TRUNC('month', m.reading_date)::date");
            case QUARTER -> new PeriodSqlParts(
                    "DATE_TRUNC('quarter', g.day_date)::date",
                    "(DATE_TRUNC('quarter', g.day_date)::date + INTERVAL '3 month - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('quarter', g.day_date)::date, 'YYYY-\"Q\"Q')",
                    "DATE_TRUNC('quarter', m.reading_date)::date");
            case YEAR -> new PeriodSqlParts(
                    "DATE_TRUNC('year', g.day_date)::date",
                    "(DATE_TRUNC('year', g.day_date)::date + INTERVAL '1 year - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('year', g.day_date)::date, 'YYYY')",
                    "DATE_TRUNC('year', m.reading_date)::date");
        };
    }

    private PeriodSqlParts buildPeriodSqlPartsForSchemeDay(PeriodScale scale) {
        // Same alignment rules as buildPeriodSqlParts(), but fact date column is sd.reading_date.
        return switch (scale) {
            case DAY -> new PeriodSqlParts(
                    "g.day_date::date",
                    "g.day_date::date",
                    "TO_CHAR(g.day_date::date, 'YYYY-MM-DD')",
                    "sd.reading_date::date");
            case WEEK -> new PeriodSqlParts(
                    "(params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7))::date",
                    "(params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7) + 6)::date",
                    "TO_CHAR((params.anchor_start + (((g.day_date::date - params.anchor_start) / 7) * 7))::date, 'YYYY-MM-DD')",
                    "(params.anchor_start + (((sd.reading_date::date - params.anchor_start) / 7) * 7))::date");
            case MONTH -> new PeriodSqlParts(
                    "DATE_TRUNC('month', g.day_date)::date",
                    "(DATE_TRUNC('month', g.day_date)::date + INTERVAL '1 month - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('month', g.day_date)::date, 'YYYY-MM')",
                    "DATE_TRUNC('month', sd.reading_date)::date");
            case QUARTER -> new PeriodSqlParts(
                    "DATE_TRUNC('quarter', g.day_date)::date",
                    "(DATE_TRUNC('quarter', g.day_date)::date + INTERVAL '3 month - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('quarter', g.day_date)::date, 'YYYY-\"Q\"Q')",
                    "DATE_TRUNC('quarter', sd.reading_date)::date");
            case YEAR -> new PeriodSqlParts(
                    "DATE_TRUNC('year', g.day_date)::date",
                    "(DATE_TRUNC('year', g.day_date)::date + INTERVAL '1 year - 1 day')::date",
                    "TO_CHAR(DATE_TRUNC('year', g.day_date)::date, 'YYYY')",
                    "DATE_TRUNC('year', sd.reading_date)::date");
        };
    }

    public Integer getDepartmentLevel(Integer parentDepartmentId) {
        String sql = """
                SELECT d.department_level
                FROM analytics_schema.dim_department_location_table d
                WHERE d.department_id = ?
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> (Integer) rs.getObject("department_level"), parentDepartmentId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public Integer getDepartmentLevelForTenant(Integer tenantId, Integer parentDepartmentId) {
        String sql = """
                SELECT d.department_level
                FROM analytics_schema.dim_department_location_table d
                WHERE d.department_id = ?
                  AND d.tenant_id = ?
                LIMIT 1
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> (Integer) rs.getObject("department_level"), parentDepartmentId, tenantId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private String resolveSchemeLgdColumn(Integer lgdLevel) {
        return switch (lgdLevel) {
            case 0 -> "parent_lgd_location_id";
            case 1 -> "level_1_lgd_id";
            case 2 -> "level_2_lgd_id";
            case 3 -> "level_3_lgd_id";
            case 4 -> "level_4_lgd_id";
            case 5 -> "level_5_lgd_id";
            case 6 -> "level_6_lgd_id";
            default -> throw new IllegalArgumentException("Unsupported lgd_level: " + lgdLevel);
        };
    }

    private String resolveSchemeDepartmentColumn(Integer departmentLevel) {
        return switch (departmentLevel) {
            case 0 -> "parent_department_location_id";
            case 1 -> "level_1_dept_id";
            case 2 -> "level_2_dept_id";
            case 3 -> "level_3_dept_id";
            case 4 -> "level_4_dept_id";
            case 5 -> "level_5_dept_id";
            case 6 -> "level_6_dept_id";
            default -> throw new IllegalArgumentException("Unsupported department_level: " + departmentLevel);
        };
    }

    private String resolveChildRegionLgdParentColumn(Integer parentLgdLevel) {
        return switch (parentLgdLevel) {
            case 0, 1 -> "level_1_lgd_id";
            case 2 -> "level_2_lgd_id";
            case 3 -> "level_3_lgd_id";
            case 4 -> "level_4_lgd_id";
            case 5 -> "level_5_lgd_id";
            default -> throw new IllegalArgumentException("Unsupported parent lgd_level for child lookup: " + parentLgdLevel);
        };
    }

    private String resolveChildRegionDepartmentParentColumn(Integer parentDepartmentLevel) {
        return switch (parentDepartmentLevel) {
            case 0, 1 -> "level_1_dept_id";
            case 2 -> "level_2_dept_id";
            case 3 -> "level_3_dept_id";
            case 4 -> "level_4_dept_id";
            case 5 -> "level_5_dept_id";
            default -> throw new IllegalArgumentException(
                    "Unsupported parent department_level for child lookup: " + parentDepartmentLevel);
        };
    }

    public record SchemeRegularityMetrics(int schemeCount, int totalSupplyDays) {
    }

    public record SchemeWaterSupplyMetrics(
            Integer schemeId,
            String schemeName,
            Long householdCount,
            Long achievedFhtcCount,
            Long plannedFhtcCount,
            Long totalWaterSuppliedLiters,
            Integer supplyDays,
            BigDecimal averageLitersPerHousehold) {
    }

    public record ChildRegionWaterSupplyMetrics(
            Integer tenantId,
            String stateCode,
            Integer lgdId,
            Integer departmentId,
            String title,
            Long totalHouseholdCount,
            Long totalAchievedFhtcCount,
            Long totalPlannedFhtcCount,
            Long totalWaterSuppliedLiters,
            Integer schemeCount,
            BigDecimal avgWaterSupplyPerScheme) {
    }

    public record ChildRegionWaterQuantityMetrics(
            Integer lgdId,
            Integer departmentId,
            String title,
            Long waterQuantity,
            Long householdCount,
            Long achievedFhtcCount,
            Long plannedFhtcCount,
            Long supplyDaysInEfficientRange) {
    }

    public record PeriodicSchemeRegularityMetrics(
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            Integer schemeCount,
            Long totalAchievedFhtcCount,
            Integer totalSupplyDays,
            Long totalWaterQuantity) {}

    public record ChildRegionSchemeRegularityMetrics(
            Integer lgdId,
            Integer departmentId,
            String title,
            Integer schemeCount,
            Integer totalSupplyDays,
            BigDecimal averageRegularity) {
    }

    public record ChildRegionReadingSubmissionMetrics(
            Integer lgdId,
            Integer departmentId,
            String title,
            Integer schemeCount,
            Integer totalSubmissionDays,
            BigDecimal readingSubmissionRate) {
    }

    public record ChildRegionPerformanceScore(
            Integer lgdId,
            Integer departmentId,
            BigDecimal averagePerformanceScore) {
    }

    public record TenantSupplyDaysInEfficientRange(
            Integer tenantId,
            Long supplyDaysInEfficientRange) {
    }

    public record NationalDashboardTenantStateMetadata(
            Integer tenantId,
            Integer lgdId,
            Integer tenantStatus) {
    }

    public record NationalDashboardStateBoundary(
            Integer tenantId,
            Integer lgdId,
            Integer tenantStatus,
            String stateCode,
            String stateTitle,
            String boundaryGeoJson) {
    }

    public record NationalDashboardLevel2LgdBoundary(
            Integer tenantId,
            Integer lgdId,
            Integer tenantStatus,
            String stateCode,
            String stateTitle,
            String title,
            String boundaryGeoJson) {
    }

    public record StateSchemeRegularityMetrics(
            Integer tenantId,
            String stateCode,
            String title,
            Integer schemeCount,
            Integer totalSupplyDays) {
    }

    public record StateReadingSubmissionMetrics(
            Integer tenantId,
            String stateCode,
            String title,
            Integer schemeCount,
            Integer totalSubmissionDays) {
    }

    public record Level2WaterSupplyMetrics(
            Integer tenantId,
            Integer tenantStatus,
            String stateCode,
            String stateTitle,
            Integer lgdId,
            String districtTitle,
            Long totalHouseholdCount,
            Long totalAchievedFhtcCount,
            Long totalPlannedFhtcCount,
            Long totalWaterSuppliedLiters,
            Integer schemeCount,
            BigDecimal avgWaterSupplyPerScheme) {
    }

    public record Level2SupplyDaysInEfficientRange(
            Integer tenantId,
            Integer lgdId,
            Long supplyDaysInEfficientRange) {
    }

    public record Level2RegularityMetrics(
            Integer tenantId,
            Integer lgdId,
            Integer schemeCount,
            Integer totalSupplyDays) {
    }

    public record Level2ReadingSubmissionMetrics(
            Integer tenantId,
            Integer lgdId,
            Integer schemeCount,
            Integer totalSubmissionDays) {
    }

    private record PeriodSqlParts(
            String periodStartFromSeries,
            String periodEndFromSeries,
            String periodLabelFromSeries,
            String periodStartFromFact) {
    }

    public record PeriodicOutageReasonSchemeCountRow(
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            String outageReason,
            Integer schemeCount) {
    }

    public record OutageReasonSchemeCount(String outageReason, Integer schemeCount) {
    }

    public record NonSubmissionReasonSchemeCount(String nonSubmissionReason, Integer schemeCount) {
    }

    public record ChildRegionRef(Integer lgdId, Integer departmentId, String title) {
    }

    public record ChildRegionOutageReasonSchemeCount(
            Integer lgdId,
            Integer departmentId,
            String outageReason,
            Integer schemeCount) {
    }

    public record ChildRegionNonSubmissionReasonSchemeCount(
            Integer lgdId,
            Integer departmentId,
            String nonSubmissionReason,
            Integer schemeCount) {
    }

    public record SchemeStatusCount(Integer activeSchemeCount, Integer inactiveSchemeCount) {
    }

    public record CriticalSchemeRow(
            Integer schemeId,
            String schemeName,
            Integer stateSchemeId,
            Integer centreSchemeId,
            LocalDate lastSuppliedDate
    ) {
    }

    public record ContinuousSchemeRow(Integer schemeId, String schemeName) {
    }

    public record SchemeSubmissionMetrics(
            Integer schemeId,
            String schemeName,
            Integer operatingStatus,
            Integer submissionDays,
            Long totalWaterSupplied,
            Integer immediateParentLgdId,
            String immediateParentLgdCName,
            String immediateParentLgdTitle,
            Integer immediateParentLgdLevel,
            Integer immediateParentDepartmentId,
            String immediateParentDepartmentCName,
            String immediateParentDepartmentTitle,
            Integer immediateParentDepartmentLevel,
            Integer level1LgdId,
            Integer level2LgdId,
            Integer level3LgdId,
            Integer level4LgdId,
            Integer level5LgdId,
            Integer level6LgdId,
            Integer level1DeptId,
            Integer level2DeptId,
            Integer level3DeptId,
            Integer level4DeptId,
            Integer level5DeptId,
            Integer level6DeptId,
            List<Integer> suppliedLgdLocationIds,
            List<String> suppliedLgdLocationCNames,
            List<String> suppliedLgdLocationTitles,
            List<Integer> suppliedLgdLocationLevels) {
    }

    public record SchemeRegularityListMetrics(
            Integer schemeId,
            String schemeName,
            Integer stateSchemeId,
            Integer centreSchemeId,
            Integer operatingStatus,
            Integer supplyDays,
            Integer submissionDays) {
    }

    public record SubmissionStatusCount(Integer compliantSubmissionCount, Integer anomalousSubmissionCount) {
    }

    public record DailyOutageReasonSchemeCount(
            LocalDate date,
            String outageReason,
            Integer schemeCount) {
    }

    public record DailyNonSubmissionReasonSchemeCount(
            LocalDate date,
            String nonSubmissionReason,
            Integer schemeCount) {
    }

    public record DailySubmissionSchemeCount(
            LocalDate date,
            Integer submittedSchemeCount) {
    }

    public record PeriodicWaterQuantityMetrics(
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            String scope,
            BigDecimal averageWaterQuantity,
            Long householdCount,
            Long achievedFhtcCount,
            Long plannedFhtcCount) {
    }
}
