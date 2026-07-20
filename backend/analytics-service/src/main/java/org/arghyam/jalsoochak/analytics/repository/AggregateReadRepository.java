package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Read side of the KPI pre-aggregation. Serves dashboard metrics from the
 * pre-rolled aggregate tables instead of recomputing from raw facts.
 *
 * <p>Arbitrary day ranges are answered by summing DAY rows of fact_region_metrics_table
 * for the region (additive measures), exactly as designed — WEEK/MONTH rows are
 * used only by the grain-selected /periodic series. Non-additive KPIs
 * (continuous / critical / distinct) are derived from fact_scheme_daily_table.</p>
 *
 * <p>region_id (lgd/department id) is only unique within a tenant + hierarchy, so
 * every lookup is tenant-scoped.</p>
 */
@Repository
@RequiredArgsConstructor
public class AggregateReadRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Additive region metrics summed over the DAY rows in [start, end]. */
    public record RegionPeriodMetrics(int schemeCount,
                                      long totalSupplyDays,
                                      long totalSubmissionDays,
                                      long totalWaterSuppliedLiters,
                                      long totalHouseholdCount,
                                      long supplyDaysInEfficientRange,
                                      long compliantSubmissionCount,
                                      long anomalousSubmissionCount) {
    }

    /**
     * Sum the DAY fact_region_metrics_table rows for a region over [start, end]. scheme_count
     * and household_count are region attributes (constant per day) so they are taken
     * as the max across the days, not summed. Returns empty when no DAY rows are
     * stored for the region/range (caller falls back to the legacy path).
     */
    public Optional<RegionPeriodMetrics> getRegionMetrics(int tenantId, String hierarchy, int regionId,
                                                          LocalDate start, LocalDate end) {
        String sql = """
                SELECT COUNT(*)                                          AS day_rows,
                       COALESCE(MAX(scheme_count), 0)                    AS scheme_count,
                       COALESCE(SUM(total_supply_days), 0)              AS supply_days,
                       COALESCE(SUM(total_submission_days), 0)          AS submission_days,
                       COALESCE(SUM(total_water_supplied_liters), 0)    AS water_liters,
                       COALESCE(MAX(total_household_count), 0)          AS household_count,
                       COALESCE(SUM(supply_days_in_efficient_range), 0) AS eff_days,
                       COALESCE(SUM(compliant_submission_count), 0)     AS compliant,
                       COALESCE(SUM(anomalous_submission_count), 0)     AS anomalous
                FROM analytics_schema.fact_region_metrics_table
                WHERE period_scale = 'DAY'
                  AND tenant_id = ?
                  AND hierarchy = ?
                  AND region_id = ?
                  AND period_start BETWEEN ? AND ?
                """;
        return jdbcTemplate.query(sql, rs -> {
            if (rs.next() && rs.getLong("day_rows") > 0) {
                return Optional.of(new RegionPeriodMetrics(
                        rs.getInt("scheme_count"),
                        rs.getLong("supply_days"),
                        rs.getLong("submission_days"),
                        rs.getLong("water_liters"),
                        rs.getLong("household_count"),
                        rs.getLong("eff_days"),
                        rs.getLong("compliant"),
                        rs.getLong("anomalous")));
            }
            return Optional.empty();
        }, tenantId, hierarchy, regionId, start, end);
    }

    /**
     * Count schemes under a region that supplied water on EVERY day of [start, end]
     * (continuous schemes), derived from fact_scheme_daily_table. Returns empty when the
     * region has no aggregated activity in the range (caller falls back to legacy),
     * which distinguishes "not yet aggregated" from a genuine zero.
     *
     * <p>{@code hierarchy} selects the LGD or department ancestor columns; a region
     * id matches exactly one level, so OR-ing the six level columns selects the
     * schemes under that node without needing to know its level.</p>
     */
    public OptionalLong getContinuousSchemeCount(int tenantId, String hierarchy, int regionId,
                                                LocalDate start, LocalDate end, int daysInRange) {
        String suffix = "DEPT".equalsIgnoreCase(hierarchy) ? "dept" : "lgd";
        String orClause = IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "level_" + i + "_" + suffix + "_id = ?")
                .collect(Collectors.joining(" OR "));
        String sql = ("""
                SELECT COUNT(*) AS active_schemes,
                       COUNT(*) FILTER (WHERE supply_days = ?) AS continuous_schemes
                FROM (
                    SELECT scheme_id, SUM(supplied) AS supply_days
                    FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ?
                      AND reading_date BETWEEN ? AND ?
                      AND (%s)
                    GROUP BY scheme_id
                ) t
                """).formatted(orClause);

        Object[] params = new Object[4 + 6];
        params[0] = daysInRange;
        params[1] = tenantId;
        params[2] = start;
        params[3] = end;
        for (int i = 0; i < 6; i++) {
            params[4 + i] = regionId;
        }

        return jdbcTemplate.query(sql, rs -> {
            if (rs.next() && rs.getLong("active_schemes") > 0) {
                return OptionalLong.of(rs.getLong("continuous_schemes"));
            }
            return OptionalLong.empty();
        }, params);
    }

    /** One pre-rolled bucket of a region's periodic series (single unified water figure). */
    public record PeriodicRegionRow(LocalDate periodStart,
                                    LocalDate periodEnd,
                                    int schemeCount,
                                    long totalSupplyDays,
                                    long totalWaterSuppliedLiters,
                                    long totalHouseholdCount,
                                    long totalAchievedFhtc,
                                    long totalPlannedFhtc) {
    }

    /**
     * Pre-rolled period buckets ({@code periodScale} = DAY|WEEK|MONTH) for a region
     * that overlap [start, end], ordered by period_start. Empty when none are stored
     * (caller falls back to legacy).
     */
    public List<PeriodicRegionRow> getPeriodicRegionMetrics(int tenantId, String hierarchy, int regionId,
                                                            String periodScale, LocalDate start, LocalDate end) {
        boolean rebucket = "QUARTER".equals(periodScale) || "YEAR".equals(periodScale);
        String sql;
        Object[] params;
        if (rebucket) {
            // QUARTER/YEAR are not pre-rolled: re-bucket the stored MONTH rows (additive).
            String trunc = "QUARTER".equals(periodScale) ? "quarter" : "year";
            String endExpr = "QUARTER".equals(periodScale)
                    ? "INTERVAL '3 month - 1 day'" : "INTERVAL '1 year - 1 day'";
            sql = ("""
                    SELECT date_trunc('%1$s', period_start)::date AS period_start,
                           (date_trunc('%1$s', period_start)::date + %2$s)::date AS period_end,
                           MAX(scheme_count) AS scheme_count,
                           SUM(total_supply_days) AS total_supply_days,
                           SUM(total_water_supplied_liters) AS total_water_supplied_liters,
                           MAX(total_household_count) AS total_household_count,
                           MAX(total_achieved_fhtc) AS total_achieved_fhtc,
                           MAX(total_planned_fhtc) AS total_planned_fhtc
                    FROM analytics_schema.fact_region_metrics_table
                    WHERE period_scale = 'MONTH' AND tenant_id = ? AND hierarchy = ? AND region_id = ?
                      AND period_start <= ? AND period_end >= ?
                    GROUP BY date_trunc('%1$s', period_start)
                    ORDER BY date_trunc('%1$s', period_start)
                    """).formatted(trunc, endExpr);
            params = new Object[]{tenantId, hierarchy, regionId, end, start};
        } else {
            sql = """
                    SELECT period_start, period_end, scheme_count, total_supply_days,
                           total_water_supplied_liters, total_household_count,
                           total_achieved_fhtc, total_planned_fhtc
                    FROM analytics_schema.fact_region_metrics_table
                    WHERE period_scale = ? AND tenant_id = ? AND hierarchy = ? AND region_id = ?
                      AND period_start <= ? AND period_end >= ?
                    ORDER BY period_start
                    """;
            params = new Object[]{periodScale, tenantId, hierarchy, regionId, end, start};
        }
        return jdbcTemplate.query(sql, (rs, n) -> new PeriodicRegionRow(
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                rs.getInt("scheme_count"),
                rs.getLong("total_supply_days"),
                rs.getLong("total_water_supplied_liters"),
                rs.getLong("total_household_count"),
                rs.getLong("total_achieved_fhtc"),
                rs.getLong("total_planned_fhtc")),
                params);
    }

    /**
     * Distinct-scheme distribution for a reason column over [start, end] from the
     * base grain (so counts are deduped across days, matching the legacy semantics).
     * {@code outage}=true reads outage_reason_code, else non_submission_reason_code.
     * Empty Optional means the region has no aggregated rows in range (fall back);
     * a present-but-empty map means aggregated with no such reasons.
     */
    public Optional<Map<String, Integer>> getReasonDistribution(int tenantId, String hierarchy, int regionId,
                                                               boolean outage, LocalDate start, LocalDate end) {
        String suffix = "DEPT".equalsIgnoreCase(hierarchy) ? "dept" : "lgd";
        String orClause = IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "level_" + i + "_" + suffix + "_id = ?")
                .collect(Collectors.joining(" OR "));
        String reasonColumn = outage ? "outage_reason_code" : "non_submission_reason_code";

        Object[] regionParams = new Object[3 + 6];
        regionParams[0] = tenantId;
        regionParams[1] = start;
        regionParams[2] = end;
        for (int i = 0; i < 6; i++) {
            regionParams[3 + i] = regionId;
        }

        String existsSql = ("""
                SELECT EXISTS (
                    SELECT 1 FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND reading_date BETWEEN ? AND ? AND (%s)
                )
                """).formatted(orClause);
        Boolean hasRows = jdbcTemplate.queryForObject(existsSql, Boolean.class, regionParams);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }

        String sql = ("""
                SELECT %1$s AS dist_key, COUNT(DISTINCT scheme_id) AS scheme_count
                FROM analytics_schema.fact_scheme_daily_table
                WHERE tenant_id = ? AND reading_date BETWEEN ? AND ? AND (%2$s) AND %1$s IS NOT NULL
                GROUP BY %1$s
                ORDER BY %1$s
                """).formatted(reasonColumn, orClause);
        Map<String, Integer> dist = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            dist.put(rs.getString("dist_key"), rs.getInt("scheme_count"));
        }, regionParams);
        return Optional.of(dist);
    }

    /** Per-scheme water-supply row (current-region scope). */
    public record SchemeWaterSupplyRow(int schemeId,
                                       String schemeName,
                                       long householdCount,
                                       long achievedFhtc,
                                       long plannedFhtc,
                                       long totalWaterSuppliedLiters,
                                       int supplyDays) {
    }

    /**
     * Per-scheme water supply for all schemes in a tenant (household_count &gt; 0),
     * with supplied-water totals + supply days from fact_scheme_daily_table over the range.
     * Empty Optional when the tenant has no aggregated rows (fall back to legacy).
     */
    public Optional<List<SchemeWaterSupplyRow>> getSchemeWaterSupply(int tenantId, LocalDate start, LocalDate end) {
        Boolean hasRows = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND reading_date BETWEEN ? AND ?
                )
                """, Boolean.class, tenantId, start, end);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }
        String sql = """
                SELECT s.scheme_id, s.scheme_name,
                       s.house_hold_count::bigint AS households,
                       COALESCE(s.fhtc_count, 0)::bigint AS achieved_fhtc,
                       COALESCE(s.planned_fhtc, 0)::bigint AS planned_fhtc,
                       COALESCE(a.water_supplied, 0) AS water_supplied,
                       COALESCE(a.supply_days, 0)::int AS supply_days
                FROM (
                    SELECT DISTINCT ON (scheme_id) scheme_id, scheme_name, house_hold_count, fhtc_count, planned_fhtc
                    FROM analytics_schema.dim_scheme_table
                    WHERE tenant_id = ?
                    ORDER BY scheme_id, COALESCE(fhtc_count, 0) DESC, COALESCE(house_hold_count, 0) DESC, COALESCE(planned_fhtc, 0) DESC
                ) s
                LEFT JOIN (
                    SELECT scheme_id, SUM(water_supplied_liters) AS water_supplied, SUM(supplied) AS supply_days
                    FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND reading_date BETWEEN ? AND ?
                    GROUP BY scheme_id
                ) a ON a.scheme_id = s.scheme_id
                WHERE s.house_hold_count IS NOT NULL AND s.house_hold_count > 0
                ORDER BY s.scheme_id
                """;
        List<SchemeWaterSupplyRow> rows = jdbcTemplate.query(sql, (rs, n) -> new SchemeWaterSupplyRow(
                rs.getInt("scheme_id"),
                rs.getString("scheme_name"),
                rs.getLong("households"),
                rs.getLong("achieved_fhtc"),
                rs.getLong("planned_fhtc"),
                rs.getLong("water_supplied"),
                rs.getInt("supply_days")),
                tenantId, tenantId, start, end);
        return Optional.of(rows);
    }

    /** One (child region, reason, distinct-scheme-count) tuple. */
    public record ChildReasonRow(int regionId, String reasonKey, int schemeCount) {
    }

    /**
     * Distinct-scheme reason distribution for each child of a parent node (outage or
     * non-submission). Empty Optional when the parent scope has no aggregated rows
     * (fall back to legacy).
     */
    public Optional<List<ChildReasonRow>> getChildReasonDistribution(int tenantId, String hierarchy,
                                                                     int parentRegionId, int parentLevel,
                                                                     boolean outage, LocalDate start, LocalDate end) {
        int childLevel = parentLevel + 1;
        if (!ALLOWED_LEVELS.contains(String.valueOf(parentLevel)) || childLevel > 6) {
            return Optional.empty();
        }
        boolean dept = "DEPT".equalsIgnoreCase(hierarchy);
        String suffix = dept ? "dept" : "lgd";
        String parentCol = "level_" + parentLevel + "_" + suffix + "_id";
        String childCol = "level_" + childLevel + "_" + suffix + "_id";
        String reasonCol = outage ? "outage_reason_code" : "non_submission_reason_code";

        Boolean hasRows = jdbcTemplate.queryForObject(("""
                SELECT EXISTS (
                    SELECT 1 FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND %s = ? AND reading_date BETWEEN ? AND ?
                )
                """).formatted(parentCol), Boolean.class, tenantId, parentRegionId, start, end);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }

        String sql = ("""
                SELECT %1$s AS region_id, %2$s AS reason, COUNT(DISTINCT scheme_id) AS scheme_count
                FROM analytics_schema.fact_scheme_daily_table
                WHERE tenant_id = ? AND %3$s = ? AND reading_date BETWEEN ? AND ?
                  AND %2$s IS NOT NULL AND %1$s IS NOT NULL
                GROUP BY %1$s, %2$s
                ORDER BY %1$s, %2$s
                """).formatted(childCol, reasonCol, parentCol);
        List<ChildReasonRow> rows = jdbcTemplate.query(sql, (rs, n) -> new ChildReasonRow(
                rs.getInt("region_id"), rs.getString("reason"), rs.getInt("scheme_count")),
                tenantId, parentRegionId, start, end);
        return Optional.of(rows);
    }

    /** Per-child-region rollup (one row per child of a parent node; single unified water figure). */
    public record ChildRegionAggRow(int regionId,
                                    String title,
                                    int schemeCount,
                                    long totalSupplyDays,
                                    long totalSubmissionDays,
                                    long totalWaterSuppliedLiters,
                                    long supplyDaysInEfficientRange,
                                    long totalHouseholdCount,
                                    long totalAchievedFhtc,
                                    long totalPlannedFhtc) {
    }

    private static final java.util.Set<String> ALLOWED_LEVELS = java.util.Set.of("1", "2", "3", "4", "5", "6");

    /**
     * Roll up the children (level {@code parentLevel + 1}) of a parent region node.
     * scheme_count + household/FHTC come from dim_scheme (authoritative); activity
     * (supply/submission/water/efficient) is LEFT-JOINed from fact_scheme_daily_table.
     * Returns empty Optional when the parent scope has no aggregated rows at all
     * (caller falls back to legacy), distinguishing "not aggregated" from "no activity".
     */
    public Optional<List<ChildRegionAggRow>> getChildRegionMetrics(int tenantId, String hierarchy,
                                                                  int parentRegionId, int parentLevel,
                                                                  LocalDate start, LocalDate end) {
        int childLevel = parentLevel + 1;
        if (!ALLOWED_LEVELS.contains(String.valueOf(parentLevel)) || childLevel > 6) {
            return Optional.empty();
        }
        boolean dept = "DEPT".equalsIgnoreCase(hierarchy);
        String suffix = dept ? "dept" : "lgd";
        String parentCol = "level_" + parentLevel + "_" + suffix + "_id";
        String childCol = "level_" + childLevel + "_" + suffix + "_id";
        String locTable = dept ? "dim_department_location_table" : "dim_lgd_location_table";
        String locIdCol = dept ? "department_id" : "lgd_id";

        Boolean hasRows = jdbcTemplate.queryForObject(("""
                SELECT EXISTS (
                    SELECT 1 FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND %s = ? AND reading_date BETWEEN ? AND ?
                )
                """).formatted(parentCol), Boolean.class, tenantId, parentRegionId, start, end);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }

        // Child membership comes from the dim_scheme mapping rows: a multi-mapped scheme counts
        // once per child region (DISTINCT ON dedup) but appears under every child it maps to,
        // and its activity is joined per mapping — the same semantics as the legacy queries.
        String sql = ("""
                SELECT c.region_id, loc.title, c.scheme_count,
                       COALESCE(a.supply_days, 0)     AS supply_days,
                       COALESCE(a.submission_days, 0) AS submission_days,
                       COALESCE(a.water, 0)           AS water,
                       COALESCE(a.eff_days, 0)        AS eff_days,
                       c.households, c.achieved_fhtc, c.planned_fhtc
                FROM (
                    SELECT region_id, tenant_id,
                           COUNT(*) AS scheme_count,
                           SUM(COALESCE(house_hold_count, 0))::bigint AS households,
                           SUM(COALESCE(fhtc_count, 0))::bigint AS achieved_fhtc,
                           SUM(COALESCE(planned_fhtc, 0))::bigint AS planned_fhtc
                    FROM (
                        SELECT DISTINCT ON (%2$s, tenant_id, scheme_id)
                               %2$s AS region_id, tenant_id, scheme_id,
                               house_hold_count, fhtc_count, planned_fhtc
                        FROM analytics_schema.dim_scheme_table
                        WHERE tenant_id = ? AND %1$s = ? AND %2$s IS NOT NULL
                        ORDER BY %2$s, tenant_id, scheme_id,
                                 COALESCE(fhtc_count, 0) DESC, COALESCE(house_hold_count, 0) DESC, COALESCE(planned_fhtc, 0) DESC
                    ) dedup
                    GROUP BY region_id, tenant_id
                ) c
                LEFT JOIN (
                    SELECT m.region_id, sd.tenant_id,
                           SUM(sd.supplied) AS supply_days,
                           SUM(sd.submitted) AS submission_days,
                           SUM(sd.water_supplied_liters) AS water,
                           SUM(sd.is_supply_efficient) AS eff_days
                    FROM (
                        SELECT DISTINCT %2$s AS region_id, tenant_id, scheme_id
                        FROM analytics_schema.dim_scheme_table
                        WHERE tenant_id = ? AND %1$s = ? AND %2$s IS NOT NULL
                    ) m
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                    WHERE sd.reading_date BETWEEN ? AND ?
                    GROUP BY m.region_id, sd.tenant_id
                ) a ON a.region_id = c.region_id AND a.tenant_id = c.tenant_id
                LEFT JOIN analytics_schema.%3$s loc
                  ON loc.%4$s = c.region_id AND loc.tenant_id = c.tenant_id
                ORDER BY c.region_id
                """).formatted(parentCol, childCol, locTable, locIdCol);

        List<ChildRegionAggRow> rows = jdbcTemplate.query(sql, (rs, n) -> new ChildRegionAggRow(
                rs.getInt("region_id"),
                rs.getString("title"),
                rs.getInt("scheme_count"),
                rs.getLong("supply_days"),
                rs.getLong("submission_days"),
                rs.getLong("water"),
                rs.getLong("eff_days"),
                rs.getLong("households"),
                rs.getLong("achieved_fhtc"),
                rs.getLong("planned_fhtc")),
                tenantId, parentRegionId, tenantId, parentRegionId, start, end);
        return Optional.of(rows);
    }

    /**
     * Critical-scheme count: schemes in the region whose last supplied date (MAX
     * reading_date where supplied) is NULL or before {@code cutoffDate}. Empty
     * Optional when the region has no aggregated rows at all (fall back to legacy,
     * so a not-yet-backfilled tenant isn't reported as all-critical).
     */
    public OptionalLong getCriticalSchemeCount(int tenantId, String hierarchy, int regionId, LocalDate cutoffDate) {
        String suffix = "DEPT".equalsIgnoreCase(hierarchy) ? "dept" : "lgd";
        String orClause = IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "level_" + i + "_" + suffix + "_id = ?")
                .collect(Collectors.joining(" OR "));

        Object[] regionParams = new Object[1 + 6];
        regionParams[0] = tenantId;
        for (int i = 0; i < 6; i++) {
            regionParams[1 + i] = regionId;
        }
        Boolean hasRows = jdbcTemplate.queryForObject(("""
                SELECT EXISTS (
                    SELECT 1 FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND (%s)
                )
                """).formatted(orClause), Boolean.class, regionParams);
        if (hasRows == null || !hasRows) {
            return OptionalLong.empty();
        }

        String sql = ("""
                WITH scope AS (
                    SELECT scheme_id
                    FROM analytics_schema.dim_scheme_table
                    WHERE tenant_id = ? AND (%s)
                ),
                last_supply AS (
                    SELECT scheme_id, MAX(reading_date) AS last_supplied_date
                    FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND supplied = 1
                    GROUP BY scheme_id
                )
                SELECT COUNT(*)::bigint
                FROM scope s
                LEFT JOIN last_supply ls ON ls.scheme_id = s.scheme_id
                WHERE ls.last_supplied_date IS NULL OR ls.last_supplied_date < ?
                """).formatted(orClause);

        // Param order: scope (tenant_id, 6x regionId in OR), last_supply (tenant_id), cutoff.
        Object[] params = new Object[]{tenantId, regionId, regionId, regionId, regionId, regionId, regionId, tenantId, cutoffDate};
        Long count = jdbcTemplate.queryForObject(sql, Long.class, params);
        return OptionalLong.of(count == null ? 0L : count);
    }

    /** Cross-tenant region row for the national dashboard (state level-1 / district level-2). */
    public record NationalRegionRow(int tenantId,
                                    int regionId,
                                    String stateCode,
                                    String stateTitle,
                                    Integer tenantStatus,
                                    String regionTitle,
                                    int schemeCount,
                                    long totalSupplyDays,
                                    long totalSubmissionDays,
                                    long totalWaterSuppliedLiters,
                                    long supplyDaysInEfficientRange,
                                    long totalHouseholdCount,
                                    long totalAchievedFhtc,
                                    long totalPlannedFhtc) {
    }

    /**
     * National rollup: every LGD region at {@code regionLevel} across all tenants,
     * summed over the DAY rows in [start, end], with state/region metadata joined in.
     * Water is the same unified supplied-water figure the region cards read.
     * Empty Optional when no DAY rows are stored at that level (fall back to legacy).
     */
    public Optional<List<NationalRegionRow>> getNationalRegionMetrics(int regionLevel, LocalDate start, LocalDate end) {
        Boolean hasRows = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM analytics_schema.fact_region_metrics_table
                    WHERE period_scale = 'DAY' AND hierarchy = 'LGD' AND region_level = ?
                      AND period_start BETWEEN ? AND ?
                )
                """, Boolean.class, regionLevel, start, end);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }
        String sql = """
                SELECT m.tenant_id, m.region_id,
                       t.state_code, t.title AS state_title, t.status AS tenant_status,
                       loc.title AS region_title,
                       MAX(m.scheme_count) AS scheme_count,
                       SUM(m.total_supply_days) AS supply_days,
                       SUM(m.total_submission_days) AS submission_days,
                       SUM(m.total_water_supplied_liters) AS water_supplied,
                       SUM(m.supply_days_in_efficient_range) AS eff_days,
                       MAX(m.total_household_count) AS households,
                       MAX(m.total_achieved_fhtc) AS achieved_fhtc,
                       MAX(m.total_planned_fhtc) AS planned_fhtc
                FROM analytics_schema.fact_region_metrics_table m
                JOIN analytics_schema.dim_tenant_table t ON t.tenant_id = m.tenant_id
                LEFT JOIN analytics_schema.dim_lgd_location_table loc
                  ON loc.lgd_id = m.region_id AND loc.tenant_id = m.tenant_id
                WHERE m.period_scale = 'DAY' AND m.hierarchy = 'LGD' AND m.region_level = ?
                  AND m.period_start BETWEEN ? AND ?
                GROUP BY m.tenant_id, m.region_id, t.state_code, t.title, t.status, loc.title
                ORDER BY m.tenant_id, m.region_id
                """;
        List<NationalRegionRow> rows = jdbcTemplate.query(sql, (rs, n) -> new NationalRegionRow(
                rs.getInt("tenant_id"),
                rs.getInt("region_id"),
                rs.getString("state_code"),
                rs.getString("state_title"),
                (Integer) rs.getObject("tenant_status"),
                rs.getString("region_title"),
                rs.getInt("scheme_count"),
                rs.getLong("supply_days"),
                rs.getLong("submission_days"),
                rs.getLong("water_supplied"),
                rs.getLong("eff_days"),
                rs.getLong("households"),
                rs.getLong("achieved_fhtc"),
                rs.getLong("planned_fhtc")),
                regionLevel, start, end);
        return Optional.of(rows);
    }
}
