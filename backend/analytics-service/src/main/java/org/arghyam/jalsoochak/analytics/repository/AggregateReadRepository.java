package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.helper.DashboardWorkStatusFilter;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p><b>Work-status filter:</b> pre-rolled fact_region_metrics_table rows carry a
 * {@code work_status_scope} — tenant-facing reads select the {@code TENANT} rows, the
 * national dashboard selects the {@code NATIONAL} rows. KPIs derived at read time from
 * the (unfiltered) base grain apply the tenant-chain filter from the SCD-2 history
 * ({@code dim_tenant_work_status_filter_table}) themselves, using the filter in force
 * at the end of the requested range — matching what the bucket build would have baked
 * in. Region membership for those derived reads comes from the dim_scheme mapping rows
 * (a multi-mapped scheme belongs to every region it maps to), not the single location
 * chain stored on the daily row.</p>
 */
@Repository
public class AggregateReadRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DashboardWorkStatusFilter workStatusFilter;

    public AggregateReadRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${analytics.dashboard.included-work-statuses:4}") String includedWorkStatusesCsv) {
        this.jdbcTemplate = jdbcTemplate;
        this.workStatusFilter = new DashboardWorkStatusFilter(includedWorkStatusesCsv);
    }

    /** {@code DATE '...'} literal for a typed LocalDate (ISO format; never user-supplied text). */
    private static String dateLiteral(LocalDate date) {
        return "DATE '" + date + "'";
    }

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
     * as the max across the days, not summed. Returns empty unless a DAY row is stored for
     * <em>every</em> day in the range (caller falls back to the legacy path): a partially
     * aggregated window — e.g. a range predating the backfill horizon — would otherwise sum
     * only the covered days and understate the range totals against the full-range divisor.
     */
    public Optional<RegionPeriodMetrics> getRegionMetrics(int tenantId, String hierarchy, int regionId,
                                                          LocalDate start, LocalDate end) {
        long expectedDays = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
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
                  AND work_status_scope = 'TENANT'
                  AND tenant_id = ?
                  AND hierarchy = ?
                  AND region_id = ?
                  AND period_start BETWEEN ? AND ?
                """;
        return jdbcTemplate.query(sql, rs -> {
            // Require full coverage: one DAY row per day in the range. Partial coverage
            // (day_rows < expectedDays) means the window is only partly aggregated, so fall
            // back to legacy rather than report an under-summed total.
            if (rs.next() && rs.getLong("day_rows") >= expectedDays) {
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
        String orClause = regionMembershipOrClause(hierarchy, "ds");
        String schemeFilter = workStatusFilter.andHistoryPredicate("ds", dateLiteral(end));
        // Region membership from the dim_scheme mapping rows (multi-mapped schemes are
        // visible under every region they map to), filtered by the tenant work-status
        // chain in force at the end of the range; per-day activity joined from the base grain.
        String sql = ("""
                SELECT COUNT(*) AS active_schemes,
                       COUNT(*) FILTER (WHERE supply_days = ?) AS continuous_schemes
                FROM (
                    SELECT sd.scheme_id, SUM(sd.supplied) AS supply_days
                    FROM (
                        SELECT DISTINCT ds.tenant_id, ds.scheme_id
                        FROM analytics_schema.dim_scheme_table ds
                        WHERE ds.tenant_id = ?
                          AND (%s)%s
                    ) m
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                    WHERE sd.reading_date BETWEEN ? AND ?
                    GROUP BY sd.scheme_id
                ) t
                """).formatted(orClause, schemeFilter);

        Object[] params = new Object[4 + 6];
        params[0] = daysInRange;
        params[1] = tenantId;
        for (int i = 0; i < 6; i++) {
            params[2 + i] = regionId;
        }
        params[8] = start;
        params[9] = end;

        return jdbcTemplate.query(sql, rs -> {
            if (rs.next() && rs.getLong("active_schemes") > 0) {
                return OptionalLong.of(rs.getLong("continuous_schemes"));
            }
            return OptionalLong.empty();
        }, params);
    }

    /** OR over the six level columns of {@code alias}: a region id matches exactly one level. */
    private static String regionMembershipOrClause(String hierarchy, String alias) {
        String suffix = "DEPT".equalsIgnoreCase(hierarchy) ? "dept" : "lgd";
        return IntStream.rangeClosed(1, 6)
                .mapToObj(i -> alias + ".level_" + i + "_" + suffix + "_id = ?")
                .collect(Collectors.joining(" OR "));
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
                    WHERE period_scale = 'MONTH' AND work_status_scope = 'TENANT'
                      AND tenant_id = ? AND hierarchy = ? AND region_id = ?
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
                    WHERE period_scale = ? AND work_status_scope = 'TENANT'
                      AND tenant_id = ? AND hierarchy = ? AND region_id = ?
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
        String orClause = regionMembershipOrClause(hierarchy, "ds");
        String schemeFilter = workStatusFilter.andHistoryPredicate("ds", dateLiteral(end));
        String reasonColumn = outage ? "outage_reason_code" : "non_submission_reason_code";

        // Existence probe (fallback decision) stays unfiltered: it answers "is this
        // region aggregated at all", not "does it have qualifying schemes".
        Object[] existsParams = new Object[3 + 6];
        existsParams[0] = tenantId;
        existsParams[1] = start;
        existsParams[2] = end;
        for (int i = 0; i < 6; i++) {
            existsParams[3 + i] = regionId;
        }
        String existsSql = ("""
                SELECT EXISTS (
                    SELECT 1
                    FROM analytics_schema.dim_scheme_table ds
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = ds.tenant_id AND sd.scheme_id = ds.scheme_id
                    WHERE ds.tenant_id = ? AND sd.reading_date BETWEEN ? AND ? AND (%s)
                )
                """).formatted(orClause);
        Boolean hasRows = jdbcTemplate.queryForObject(existsSql, Boolean.class, existsParams);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }

        // Membership via mapping rows + tenant-chain filter as of the range end.
        Object[] params = new Object[1 + 6 + 2];
        params[0] = tenantId;
        for (int i = 0; i < 6; i++) {
            params[1 + i] = regionId;
        }
        params[7] = start;
        params[8] = end;
        String sql = ("""
                SELECT sd.%1$s AS dist_key, COUNT(DISTINCT sd.scheme_id) AS scheme_count
                FROM (
                    SELECT DISTINCT ds.tenant_id, ds.scheme_id
                    FROM analytics_schema.dim_scheme_table ds
                    WHERE ds.tenant_id = ? AND (%2$s)%3$s
                ) m
                JOIN analytics_schema.fact_scheme_daily_table sd
                  ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                WHERE sd.reading_date BETWEEN ? AND ? AND sd.%1$s IS NOT NULL
                GROUP BY sd.%1$s
                ORDER BY sd.%1$s
                """).formatted(reasonColumn, orClause, schemeFilter);
        Map<String, Integer> dist = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            dist.put(rs.getString("dist_key"), rs.getInt("scheme_count"));
        }, params);
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
                    SELECT DISTINCT ON (ds.scheme_id) ds.scheme_id, ds.scheme_name, ds.house_hold_count, ds.fhtc_count, ds.planned_fhtc
                    FROM analytics_schema.dim_scheme_table ds
                    WHERE ds.tenant_id = ?%s
                    ORDER BY ds.scheme_id, COALESCE(ds.fhtc_count, 0) DESC, COALESCE(ds.house_hold_count, 0) DESC, COALESCE(ds.planned_fhtc, 0) DESC
                ) s
                LEFT JOIN (
                    SELECT scheme_id, SUM(water_supplied_liters) AS water_supplied, SUM(supplied) AS supply_days
                    FROM analytics_schema.fact_scheme_daily_table
                    WHERE tenant_id = ? AND reading_date BETWEEN ? AND ?
                    GROUP BY scheme_id
                ) a ON a.scheme_id = s.scheme_id
                WHERE s.house_hold_count IS NOT NULL AND s.house_hold_count > 0
                ORDER BY s.scheme_id
                """.formatted(workStatusFilter.andHistoryPredicate("ds", dateLiteral(end)));
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
                    SELECT 1
                    FROM analytics_schema.dim_scheme_table ds
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = ds.tenant_id AND sd.scheme_id = ds.scheme_id
                    WHERE ds.tenant_id = ? AND ds.%s = ? AND sd.reading_date BETWEEN ? AND ?
                )
                """).formatted(parentCol), Boolean.class, tenantId, parentRegionId, start, end);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }

        // Child membership from the dim_scheme mapping rows (a multi-mapped scheme surfaces
        // under every child it maps to), filtered by the tenant chain as of the range end.
        String schemeFilter = workStatusFilter.andHistoryPredicate("ds", dateLiteral(end));
        String sql = ("""
                SELECT m.region_id, sd.%2$s AS reason, COUNT(DISTINCT sd.scheme_id) AS scheme_count
                FROM (
                    SELECT DISTINCT ds.%1$s AS region_id, ds.tenant_id, ds.scheme_id
                    FROM analytics_schema.dim_scheme_table ds
                    WHERE ds.tenant_id = ? AND ds.%3$s = ? AND ds.%1$s IS NOT NULL%4$s
                ) m
                JOIN analytics_schema.fact_scheme_daily_table sd
                  ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                WHERE sd.reading_date BETWEEN ? AND ?
                  AND sd.%2$s IS NOT NULL
                GROUP BY m.region_id, sd.%2$s
                ORDER BY m.region_id, sd.%2$s
                """).formatted(childCol, reasonCol, parentCol, schemeFilter);
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
                    SELECT 1
                    FROM analytics_schema.dim_scheme_table ds
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = ds.tenant_id AND sd.scheme_id = ds.scheme_id
                    WHERE ds.tenant_id = ? AND ds.%s = ? AND sd.reading_date BETWEEN ? AND ?
                )
                """).formatted(parentCol), Boolean.class, tenantId, parentRegionId, start, end);
        if (hasRows == null || !hasRows) {
            return Optional.empty();
        }

        // Child membership comes from the dim_scheme mapping rows: a multi-mapped scheme counts
        // once per child region (DISTINCT ON dedup) but appears under every child it maps to,
        // and its activity is joined per mapping — the same semantics as the legacy queries.
        // Both subqueries apply the tenant-chain filter as of the range end.
        String schemeFilter = workStatusFilter.andHistoryPredicate("ds", dateLiteral(end));
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
                        SELECT DISTINCT ON (ds.%2$s, ds.tenant_id, ds.scheme_id)
                               ds.%2$s AS region_id, ds.tenant_id, ds.scheme_id,
                               ds.house_hold_count, ds.fhtc_count, ds.planned_fhtc
                        FROM analytics_schema.dim_scheme_table ds
                        WHERE ds.tenant_id = ? AND ds.%1$s = ? AND ds.%2$s IS NOT NULL%5$s
                        ORDER BY ds.%2$s, ds.tenant_id, ds.scheme_id,
                                 COALESCE(ds.fhtc_count, 0) DESC, COALESCE(ds.house_hold_count, 0) DESC, COALESCE(ds.planned_fhtc, 0) DESC
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
                        SELECT DISTINCT ds.%2$s AS region_id, ds.tenant_id, ds.scheme_id
                        FROM analytics_schema.dim_scheme_table ds
                        WHERE ds.tenant_id = ? AND ds.%1$s = ? AND ds.%2$s IS NOT NULL%5$s
                    ) m
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                    WHERE sd.reading_date BETWEEN ? AND ?
                    GROUP BY m.region_id, sd.tenant_id
                ) a ON a.region_id = c.region_id AND a.tenant_id = c.tenant_id
                LEFT JOIN analytics_schema.%3$s loc
                  ON loc.%4$s = c.region_id AND loc.tenant_id = c.tenant_id
                ORDER BY c.region_id
                """).formatted(parentCol, childCol, locTable, locIdCol, schemeFilter);

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
     * Optional when the region has no aggregated rows on/after {@code cutoffDate}
     * (fall back to legacy): a store not yet backfilled up to the cutoff would
     * otherwise show every scheme's last supply as "before cutoff" and report the
     * whole region as critical.
     */
    public OptionalLong getCriticalSchemeCount(int tenantId, String hierarchy, int regionId, LocalDate cutoffDate) {
        String orClause = regionMembershipOrClause(hierarchy, "ds");
        // Critical is a current-state KPI, so the filter in force today applies.
        String schemeFilter = workStatusFilter.andHistoryPredicate("ds", "CURRENT_DATE");

        Object[] existsParams = new Object[1 + 6 + 1];
        existsParams[0] = tenantId;
        for (int i = 0; i < 6; i++) {
            existsParams[1 + i] = regionId;
        }
        existsParams[1 + 6] = cutoffDate;
        Boolean hasRows = jdbcTemplate.queryForObject(("""
                SELECT EXISTS (
                    SELECT 1
                    FROM analytics_schema.dim_scheme_table ds
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = ds.tenant_id AND sd.scheme_id = ds.scheme_id
                    WHERE ds.tenant_id = ? AND (%s) AND sd.reading_date >= ?
                )
                """).formatted(orClause), Boolean.class, existsParams);
        if (hasRows == null || !hasRows) {
            return OptionalLong.empty();
        }

        String sql = ("""
                WITH scope AS (
                    SELECT DISTINCT ds.scheme_id
                    FROM analytics_schema.dim_scheme_table ds
                    WHERE ds.tenant_id = ? AND (%s)%s
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
                """).formatted(orClause, schemeFilter);

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
                    WHERE period_scale = 'DAY' AND work_status_scope = 'NATIONAL'
                      AND hierarchy = 'LGD' AND region_level = ?
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
                WHERE m.period_scale = 'DAY' AND m.work_status_scope = 'NATIONAL'
                  AND m.hierarchy = 'LGD' AND m.region_level = ?
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
