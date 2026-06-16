package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Set-based population of the pre-aggregation tables (agg_scheme_daily ->
 * agg_region_metrics / agg_region_distribution, and agg_submission_activity_hourly).
 *
 * <p>All writes are idempotent UPSERTs keyed on the natural key, so a re-run for an
 * overlapping window simply refreshes the affected rows (used by the N-day
 * re-aggregation lookback that absorbs late / corrected readings).</p>
 *
 * <p>scheme_id and lgd/department ids are only unique within a tenant, so every
 * grain key and every join is tenant-scoped.</p>
 */
@Repository
@RequiredArgsConstructor
public class AggregationRepository {

    private final JdbcTemplate jdbcTemplate;

    /** Whitelisted hierarchy level columns on dim_scheme_table / agg_scheme_daily. */
    private static final Set<String> ALLOWED_LEVEL_COLUMNS = Set.of(
            "level_1_lgd_id", "level_2_lgd_id", "level_3_lgd_id",
            "level_4_lgd_id", "level_5_lgd_id", "level_6_lgd_id",
            "level_1_dept_id", "level_2_dept_id", "level_3_dept_id",
            "level_4_dept_id", "level_5_dept_id", "level_6_dept_id");

    // ============================================================
    // Tier 1 — base scheme x day summary
    // ============================================================

    /**
     * Rebuild agg_scheme_daily for every (tenant, scheme, day) that has a meter
     * reading or a water-quantity row in [{@code from}, {@code to}]. Norm values are
     * snapshotted from the SCD-2 history effective on each reading_date.
     */
    public int upsertSchemeDaily(LocalDate from, LocalDate to) {
        String sql = """
                WITH days AS (
                    SELECT DISTINCT tenant_id, scheme_id, reading_date AS d
                    FROM analytics_schema.fact_meter_reading_table
                    WHERE reading_date BETWEEN ? AND ?
                    UNION
                    SELECT DISTINCT tenant_id, scheme_id, date AS d
                    FROM analytics_schema.fact_water_quantity_table
                    WHERE date BETWEEN ? AND ?
                ),
                mr AS (
                    SELECT tenant_id, scheme_id, reading_date AS d,
                           1 AS submitted,
                           MAX(CASE WHEN confirmed_reading > 0 THEN 1 ELSE 0 END) AS supplied,
                           SUM(CASE WHEN confirmed_reading > 0 THEN confirmed_reading ELSE 0 END) AS confirmed_reading_total,
                           COUNT(*) FILTER (
                               WHERE extracted_reading IS NOT NULL
                                 AND extracted_reading = confirmed_reading)            AS compliant_count,
                           COUNT(*) FILTER (
                               WHERE extracted_reading IS NOT NULL
                                 AND extracted_reading IS DISTINCT FROM confirmed_reading) AS anomalous_count
                    FROM analytics_schema.fact_meter_reading_table
                    WHERE reading_date BETWEEN ? AND ?
                    GROUP BY tenant_id, scheme_id, reading_date
                ),
                wq AS (
                    SELECT tenant_id, scheme_id, date AS d,
                           SUM(water_quantity)        AS water_quantity_liters,
                           SUM(water_quantity) FILTER (
                               WHERE (submission_status = 1 OR submission_status IS NULL)
                                 AND water_quantity > 0)  AS water_quantity_submitted_liters,
                           COUNT(*)                   AS water_quantity_row_count,
                           MAX(outage_reason)         AS outage_reason_code,
                           MAX(non_submission_reason) AS non_submission_reason_code
                    FROM analytics_schema.fact_water_quantity_table
                    WHERE date BETWEEN ? AND ?
                    GROUP BY tenant_id, scheme_id, date
                )
                INSERT INTO analytics_schema.agg_scheme_daily (
                    tenant_id, scheme_id, reading_date,
                    level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                    level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                    submitted, supplied, water_quantity_liters, water_quantity_submitted_liters,
                    water_quantity_row_count, confirmed_reading_total,
                    compliant_count, anomalous_count,
                    household_count, achieved_fhtc_count, planned_fhtc_count, in_efficient_range,
                    outage_reason_code, non_submission_reason_code, scheme_status_code,
                    snap_required_lpcd, snap_persons_per_hh, snap_over_pct, snap_under_pct,
                    computed_at, is_final
                )
                SELECT days.tenant_id, days.scheme_id, days.d,
                       ds.level_1_lgd_id, ds.level_2_lgd_id, ds.level_3_lgd_id, ds.level_4_lgd_id, ds.level_5_lgd_id, ds.level_6_lgd_id,
                       ds.level_1_dept_id, ds.level_2_dept_id, ds.level_3_dept_id, ds.level_4_dept_id, ds.level_5_dept_id, ds.level_6_dept_id,
                       COALESCE(mr.submitted, 0),
                       COALESCE(mr.supplied, 0),
                       COALESCE(wq.water_quantity_liters, 0),
                       COALESCE(wq.water_quantity_submitted_liters, 0),
                       COALESCE(wq.water_quantity_row_count, 0),
                       COALESCE(mr.confirmed_reading_total, 0),
                       COALESCE(mr.compliant_count, 0),
                       COALESCE(mr.anomalous_count, 0),
                       COALESCE(ds.house_hold_count, 0),
                       COALESCE(ds.fhtc_count, 0),
                       COALESCE(ds.planned_fhtc, 0),
                       CASE
                           WHEN norm.required_lpcd IS NOT NULL
                                AND COALESCE(wq.water_quantity_liters, 0) > 0
                                AND COALESCE(wq.water_quantity_liters, 0)::numeric BETWEEN
                                    (norm.required_lpcd::numeric
                                        * (COALESCE(ds.fhtc_count, 0)::numeric * COALESCE(norm.person_count_per_household, 5)::numeric))
                                    * (1 - COALESCE(norm.under_supply_range_percentage, 0)::numeric / 100)
                                AND (norm.required_lpcd::numeric
                                        * (COALESCE(ds.fhtc_count, 0)::numeric * COALESCE(norm.person_count_per_household, 5)::numeric))
                                    * (1 + COALESCE(norm.over_supply_range_percentage, 0)::numeric / 100)
                           THEN 1 ELSE 0
                       END,
                       wq.outage_reason_code,
                       wq.non_submission_reason_code,
                       CAST(ds.operating_status AS varchar),
                       norm.required_lpcd, norm.person_count_per_household,
                       norm.over_supply_range_percentage, norm.under_supply_range_percentage,
                       CURRENT_TIMESTAMP, (days.d < CURRENT_DATE)
                FROM days
                JOIN analytics_schema.dim_scheme_table ds
                  ON ds.scheme_id = days.scheme_id AND ds.tenant_id = days.tenant_id
                LEFT JOIN LATERAL (
                    SELECT n.required_lpcd, n.person_count_per_household,
                           n.over_supply_range_percentage, n.under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_water_norm n
                    WHERE n.tenant_id = ds.tenant_id
                      AND n.effective_from <= days.d
                      AND (n.effective_to IS NULL OR n.effective_to > days.d)
                    ORDER BY n.effective_from DESC
                    LIMIT 1
                ) norm ON TRUE
                LEFT JOIN mr ON mr.tenant_id = days.tenant_id AND mr.scheme_id = days.scheme_id AND mr.d = days.d
                LEFT JOIN wq ON wq.tenant_id = days.tenant_id AND wq.scheme_id = days.scheme_id AND wq.d = days.d
                ON CONFLICT (tenant_id, scheme_id, reading_date) DO UPDATE SET
                    level_1_lgd_id = EXCLUDED.level_1_lgd_id, level_2_lgd_id = EXCLUDED.level_2_lgd_id,
                    level_3_lgd_id = EXCLUDED.level_3_lgd_id, level_4_lgd_id = EXCLUDED.level_4_lgd_id,
                    level_5_lgd_id = EXCLUDED.level_5_lgd_id, level_6_lgd_id = EXCLUDED.level_6_lgd_id,
                    level_1_dept_id = EXCLUDED.level_1_dept_id, level_2_dept_id = EXCLUDED.level_2_dept_id,
                    level_3_dept_id = EXCLUDED.level_3_dept_id, level_4_dept_id = EXCLUDED.level_4_dept_id,
                    level_5_dept_id = EXCLUDED.level_5_dept_id, level_6_dept_id = EXCLUDED.level_6_dept_id,
                    submitted = EXCLUDED.submitted, supplied = EXCLUDED.supplied,
                    water_quantity_liters = EXCLUDED.water_quantity_liters,
                    water_quantity_submitted_liters = EXCLUDED.water_quantity_submitted_liters,
                    water_quantity_row_count = EXCLUDED.water_quantity_row_count,
                    confirmed_reading_total = EXCLUDED.confirmed_reading_total,
                    compliant_count = EXCLUDED.compliant_count, anomalous_count = EXCLUDED.anomalous_count,
                    household_count = EXCLUDED.household_count, achieved_fhtc_count = EXCLUDED.achieved_fhtc_count,
                    planned_fhtc_count = EXCLUDED.planned_fhtc_count, in_efficient_range = EXCLUDED.in_efficient_range,
                    outage_reason_code = EXCLUDED.outage_reason_code,
                    non_submission_reason_code = EXCLUDED.non_submission_reason_code,
                    scheme_status_code = EXCLUDED.scheme_status_code,
                    snap_required_lpcd = EXCLUDED.snap_required_lpcd, snap_persons_per_hh = EXCLUDED.snap_persons_per_hh,
                    snap_over_pct = EXCLUDED.snap_over_pct, snap_under_pct = EXCLUDED.snap_under_pct,
                    computed_at = EXCLUDED.computed_at, is_final = EXCLUDED.is_final
                """;
        return jdbcTemplate.update(sql, from, to, from, to, from, to, from, to);
    }

    // ============================================================
    // Tier 2 — region rollups (both hierarchies, all 6 levels)
    // ============================================================

    /** Roll agg_scheme_daily into agg_region_metrics for one bucket, across both hierarchies and all levels. */
    public int upsertRegionMetrics(PeriodScale scale, LocalDate periodStart, LocalDate periodEnd, boolean isFinal) {
        int total = 0;
        for (int level = 1; level <= 6; level++) {
            total += upsertRegionMetricsForLevel(scale, periodStart, periodEnd, "LGD",
                    "level_" + level + "_lgd_id", level, isFinal);
            total += upsertRegionMetricsForLevel(scale, periodStart, periodEnd, "DEPT",
                    "level_" + level + "_dept_id", level, isFinal);
        }
        return total;
    }

    private int upsertRegionMetricsForLevel(PeriodScale scale, LocalDate periodStart, LocalDate periodEnd,
                                            String hierarchy, String levelColumn, int level, boolean isFinal) {
        requireAllowedColumn(levelColumn);
        long daysInRange = java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;

        // s = scheme set per region (from dim_scheme — authoritative, includes inactive / no-activity schemes)
        // a = additive activity sums per region (from agg_scheme_daily)
        // ps = per-scheme activity (for non-additive continuous / distinct-submitting counts)
        String sql = ("""
                INSERT INTO analytics_schema.agg_region_metrics (
                    period_scale, period_start, period_end, tenant_id, hierarchy, region_level, region_id,
                    days_in_range, scheme_count, total_supply_days, total_submission_days,
                    active_scheme_count, inactive_scheme_count, total_water_supplied_liters,
                    total_water_submitted_liters, water_quantity_row_count, total_confirmed_reading,
                    total_household_count, total_achieved_fhtc, total_planned_fhtc,
                    supply_days_in_efficient_range, compliant_submission_count, anomalous_submission_count,
                    continuous_scheme_count, critical_scheme_count, distinct_submitting_schemes,
                    average_regularity, reading_submission_rate, avg_water_supply_per_scheme,
                    snap_required_lpcd, snap_persons_per_hh, snap_over_pct, snap_under_pct,
                    computed_at, is_final
                )
                SELECT ?, ?, ?, s.tenant_id, ?, ?, s.region_id,
                       ?, s.scheme_count,
                       COALESCE(a.total_supply_days, 0), COALESCE(a.total_submission_days, 0),
                       s.active_scheme_count, s.inactive_scheme_count,
                       COALESCE(a.total_water_supplied_liters, 0),
                       COALESCE(a.total_water_submitted_liters, 0),
                       COALESCE(a.water_quantity_row_count, 0),
                       COALESCE(a.total_confirmed_reading, 0),
                       s.total_household_count, s.total_achieved_fhtc, s.total_planned_fhtc,
                       COALESCE(a.supply_days_in_efficient_range, 0),
                       COALESCE(a.compliant_submission_count, 0), COALESCE(a.anomalous_submission_count, 0),
                       COALESCE(c.continuous_scheme_count, 0),
                       COALESCE(c.never_submitted_scheme_count, 0),
                       COALESCE(c.distinct_submitting_schemes, 0),
                       CASE WHEN s.scheme_count > 0 AND ? > 0
                            THEN ROUND(COALESCE(a.total_supply_days, 0)::numeric / (s.scheme_count * ?) * 100, 2)
                            ELSE 0 END,
                       CASE WHEN s.scheme_count > 0 AND ? > 0
                            THEN ROUND(COALESCE(a.total_submission_days, 0)::numeric / (s.scheme_count * ?) * 100, 2)
                            ELSE 0 END,
                       CASE WHEN s.scheme_count > 0
                            THEN ROUND(COALESCE(a.total_water_supplied_liters, 0)::numeric / s.scheme_count, 2)
                            ELSE 0 END,
                       a.snap_required_lpcd, a.snap_persons_per_hh, a.snap_over_pct, a.snap_under_pct,
                       CURRENT_TIMESTAMP, ?
                FROM (
                    SELECT %1$s AS region_id, ds.tenant_id,
                           COUNT(*) AS scheme_count,
                           SUM(CASE WHEN operating_status > 0 THEN 1 ELSE 0 END) AS active_scheme_count,
                           SUM(CASE WHEN operating_status > 0 THEN 0 ELSE 1 END) AS inactive_scheme_count,
                           SUM(COALESCE(house_hold_count, 0))::bigint AS total_household_count,
                           SUM(COALESCE(fhtc_count, 0))::bigint AS total_achieved_fhtc,
                           SUM(COALESCE(planned_fhtc, 0))::bigint AS total_planned_fhtc
                    FROM analytics_schema.dim_scheme_table ds
                    WHERE %1$s IS NOT NULL
                    GROUP BY %1$s, ds.tenant_id
                ) s
                LEFT JOIN (
                    SELECT %1$s AS region_id, tenant_id,
                           SUM(supplied) AS total_supply_days,
                           SUM(submitted) AS total_submission_days,
                           SUM(water_quantity_liters) AS total_water_supplied_liters,
                           SUM(water_quantity_submitted_liters) AS total_water_submitted_liters,
                           SUM(water_quantity_row_count) AS water_quantity_row_count,
                           SUM(confirmed_reading_total) AS total_confirmed_reading,
                           SUM(in_efficient_range) AS supply_days_in_efficient_range,
                           SUM(compliant_count) AS compliant_submission_count,
                           SUM(anomalous_count) AS anomalous_submission_count,
                           MAX(snap_required_lpcd) AS snap_required_lpcd,
                           MAX(snap_persons_per_hh) AS snap_persons_per_hh,
                           MAX(snap_over_pct) AS snap_over_pct,
                           MAX(snap_under_pct) AS snap_under_pct
                    FROM analytics_schema.agg_scheme_daily
                    WHERE reading_date BETWEEN ? AND ?
                    GROUP BY %1$s, tenant_id
                ) a ON a.region_id = s.region_id AND a.tenant_id = s.tenant_id
                LEFT JOIN (
                    SELECT region_id, tenant_id,
                           COUNT(*) FILTER (WHERE supply_days = ?) AS continuous_scheme_count,
                           COUNT(*) FILTER (WHERE submission_days > 0) AS distinct_submitting_schemes,
                           COUNT(*) FILTER (WHERE submission_days = 0) AS never_submitted_scheme_count
                    FROM (
                        SELECT %1$s AS region_id, tenant_id, scheme_id,
                               SUM(supplied) AS supply_days,
                               SUM(submitted) AS submission_days
                        FROM analytics_schema.agg_scheme_daily
                        WHERE reading_date BETWEEN ? AND ?
                        GROUP BY %1$s, tenant_id, scheme_id
                    ) ps
                    GROUP BY region_id, tenant_id
                ) c ON c.region_id = s.region_id AND c.tenant_id = s.tenant_id
                ON CONFLICT (period_scale, period_start, tenant_id, hierarchy, region_level, region_id) DO UPDATE SET
                    period_end = EXCLUDED.period_end,
                    days_in_range = EXCLUDED.days_in_range, scheme_count = EXCLUDED.scheme_count,
                    total_supply_days = EXCLUDED.total_supply_days, total_submission_days = EXCLUDED.total_submission_days,
                    active_scheme_count = EXCLUDED.active_scheme_count, inactive_scheme_count = EXCLUDED.inactive_scheme_count,
                    total_water_supplied_liters = EXCLUDED.total_water_supplied_liters,
                    total_water_submitted_liters = EXCLUDED.total_water_submitted_liters,
                    water_quantity_row_count = EXCLUDED.water_quantity_row_count,
                    total_confirmed_reading = EXCLUDED.total_confirmed_reading,
                    total_household_count = EXCLUDED.total_household_count,
                    total_achieved_fhtc = EXCLUDED.total_achieved_fhtc, total_planned_fhtc = EXCLUDED.total_planned_fhtc,
                    supply_days_in_efficient_range = EXCLUDED.supply_days_in_efficient_range,
                    compliant_submission_count = EXCLUDED.compliant_submission_count,
                    anomalous_submission_count = EXCLUDED.anomalous_submission_count,
                    continuous_scheme_count = EXCLUDED.continuous_scheme_count,
                    critical_scheme_count = EXCLUDED.critical_scheme_count,
                    distinct_submitting_schemes = EXCLUDED.distinct_submitting_schemes,
                    average_regularity = EXCLUDED.average_regularity,
                    reading_submission_rate = EXCLUDED.reading_submission_rate,
                    avg_water_supply_per_scheme = EXCLUDED.avg_water_supply_per_scheme,
                    snap_required_lpcd = EXCLUDED.snap_required_lpcd, snap_persons_per_hh = EXCLUDED.snap_persons_per_hh,
                    snap_over_pct = EXCLUDED.snap_over_pct, snap_under_pct = EXCLUDED.snap_under_pct,
                    computed_at = EXCLUDED.computed_at, is_final = EXCLUDED.is_final
                """).formatted(levelColumn);

        return jdbcTemplate.update(sql,
                scale.name(), periodStart, periodEnd, hierarchy, level,
                daysInRange,
                daysInRange, daysInRange,   // average_regularity denominators
                daysInRange, daysInRange,   // reading_submission_rate denominators
                isFinal,
                periodStart, periodEnd,     // activity sums window
                daysInRange,                // continuous threshold
                periodStart, periodEnd);    // per-scheme window
    }

    /** Roll agg_scheme_daily reason/status columns into the long-format distribution table for one bucket. */
    public int upsertRegionDistribution(PeriodScale scale, LocalDate periodStart, LocalDate periodEnd, boolean isFinal) {
        int total = 0;
        for (int level = 1; level <= 6; level++) {
            total += upsertDistributionForLevel(scale, periodStart, periodEnd, "LGD",
                    "level_" + level + "_lgd_id", level, isFinal);
            total += upsertDistributionForLevel(scale, periodStart, periodEnd, "DEPT",
                    "level_" + level + "_dept_id", level, isFinal);
        }
        return total;
    }

    private int upsertDistributionForLevel(PeriodScale scale, LocalDate periodStart, LocalDate periodEnd,
                                           String hierarchy, String levelColumn, int level, boolean isFinal) {
        requireAllowedColumn(levelColumn);
        // OUTAGE_REASON and NON_SUBMISSION_REASON: distinct schemes per reason in the bucket.
        String sql = ("""
                INSERT INTO analytics_schema.agg_region_distribution (
                    period_scale, period_start, period_end, tenant_id, hierarchy, region_level, region_id,
                    dist_type, dist_key, scheme_count, computed_at, is_final
                )
                SELECT ?, ?, ?, tenant_id, ?, ?, region_id, dist_type, dist_key,
                       COUNT(DISTINCT scheme_id), CURRENT_TIMESTAMP, ?
                FROM (
                    SELECT %1$s AS region_id, tenant_id, scheme_id,
                           'OUTAGE_REASON' AS dist_type, outage_reason_code AS dist_key
                    FROM analytics_schema.agg_scheme_daily
                    WHERE reading_date BETWEEN ? AND ? AND outage_reason_code IS NOT NULL
                    UNION ALL
                    SELECT %1$s AS region_id, tenant_id, scheme_id,
                           'NON_SUBMISSION_REASON' AS dist_type, non_submission_reason_code AS dist_key
                    FROM analytics_schema.agg_scheme_daily
                    WHERE reading_date BETWEEN ? AND ? AND non_submission_reason_code IS NOT NULL
                ) x
                WHERE region_id IS NOT NULL
                GROUP BY tenant_id, region_id, dist_type, dist_key
                ON CONFLICT (period_scale, period_start, tenant_id, hierarchy, region_level, region_id, dist_type, dist_key)
                DO UPDATE SET period_end = EXCLUDED.period_end,
                              scheme_count = EXCLUDED.scheme_count,
                              computed_at = EXCLUDED.computed_at,
                              is_final = EXCLUDED.is_final
                """).formatted(levelColumn);
        return jdbcTemplate.update(sql,
                scale.name(), periodStart, periodEnd, hierarchy, level, isFinal,
                periodStart, periodEnd, periodStart, periodEnd);
    }

    // ============================================================
    // Hourly submission/ingestion activity (HOUR grain)
    // ============================================================

    /** Aggregate reading-submission activity for the hour starting at {@code hourStart} (level-1 LGD region). */
    public int upsertSubmissionActivityHourly(LocalDateTime hourStart) {
        LocalDateTime hourEnd = hourStart.plusHours(1);
        String sql = """
                INSERT INTO analytics_schema.agg_submission_activity_hourly (
                    hour_start, tenant_id, hierarchy, region_level, region_id,
                    submission_count, distinct_scheme_count, computed_at
                )
                SELECT date_trunc('hour', fmr.reading_at) AS hour_start,
                       fmr.tenant_id, 'LGD', 1, ds.level_1_lgd_id,
                       COUNT(*), COUNT(DISTINCT fmr.scheme_id), CURRENT_TIMESTAMP
                FROM analytics_schema.fact_meter_reading_table fmr
                JOIN analytics_schema.dim_scheme_table ds
                  ON ds.scheme_id = fmr.scheme_id AND ds.tenant_id = fmr.tenant_id
                WHERE fmr.reading_at >= ? AND fmr.reading_at < ?
                  AND ds.level_1_lgd_id IS NOT NULL
                GROUP BY date_trunc('hour', fmr.reading_at), fmr.tenant_id, ds.level_1_lgd_id
                ON CONFLICT (hour_start, tenant_id, hierarchy, region_level, region_id) DO UPDATE SET
                    submission_count = EXCLUDED.submission_count,
                    distinct_scheme_count = EXCLUDED.distinct_scheme_count,
                    computed_at = EXCLUDED.computed_at
                """;
        return jdbcTemplate.update(sql, hourStart, hourEnd);
    }

    private static void requireAllowedColumn(String column) {
        if (!ALLOWED_LEVEL_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("Illegal level column: " + column);
        }
    }
}
