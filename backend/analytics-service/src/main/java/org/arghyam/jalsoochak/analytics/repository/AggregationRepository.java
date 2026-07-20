package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Set-based population of the pre-aggregation tables (fact_scheme_daily_table ->
 * fact_region_metrics_table / fact_region_distribution_table, and fact_submission_activity_hourly_table).
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

    /** Whitelisted hierarchy level columns on dim_scheme_table / fact_scheme_daily_table. */
    private static final Set<String> ALLOWED_LEVEL_COLUMNS = Set.of(
            "level_1_lgd_id", "level_2_lgd_id", "level_3_lgd_id",
            "level_4_lgd_id", "level_5_lgd_id", "level_6_lgd_id",
            "level_1_dept_id", "level_2_dept_id", "level_3_dept_id",
            "level_4_dept_id", "level_5_dept_id", "level_6_dept_id");

    // ============================================================
    // Tier 1 — base scheme x day summary
    // ============================================================

    /**
     * Rebuild fact_scheme_daily_table for every (tenant, scheme, day) that has a meter
     * reading or a water-quantity row in [{@code from}, {@code to}]. Norm values are
     * snapshotted from the SCD-2 history effective on each reading_date.
     *
     * <p>Water follows the canonical supplied-water rule shared with the legacy dashboard
     * SQL ({@code SchemeRegularityRepository}): take the latest water-quantity row per
     * (tenant, scheme, day) — {@code ORDER BY updated_at DESC, id DESC} — and count it only
     * when {@code submission_status = 1 (SUBMITTED) OR IS NULL (legacy)} AND
     * {@code water_quantity > 0}. {@code supplied} is 1 exactly when that day's water
     * qualifies, so it doubles as the qualifying-row count. {@code dim_scheme_table} holds
     * one row per parent mapping, so the location chain is taken from a deterministic
     * representative row (highest FHTC — same dedup ordering as the legacy queries);
     * without that dedup a multi-mapped scheme would make the UPSERT hit the same key
     * twice and fail.</p>
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
                    -- Latest row per (tenant, scheme, day); qualifies under the supplied-water rule.
                    SELECT tenant_id, scheme_id, d,
                           CASE WHEN (submission_status = 1 OR submission_status IS NULL)
                                 AND water_quantity > 0
                                THEN water_quantity ELSE 0 END AS water_supplied_liters,
                           CASE WHEN (submission_status = 1 OR submission_status IS NULL)
                                 AND water_quantity > 0
                                THEN 1 ELSE 0 END              AS supplied,
                           outage_reason         AS outage_reason_code,
                           non_submission_reason AS non_submission_reason_code
                    FROM (
                        SELECT DISTINCT ON (tenant_id, scheme_id, date)
                               tenant_id, scheme_id, date AS d,
                               water_quantity, submission_status, outage_reason, non_submission_reason
                        FROM analytics_schema.fact_water_quantity_table
                        WHERE date BETWEEN ? AND ?
                        ORDER BY tenant_id, scheme_id, date, updated_at DESC, id DESC
                    ) latest
                )
                INSERT INTO analytics_schema.fact_scheme_daily_table (
                    tenant_id, scheme_id, reading_date,
                    level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                    level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                    submitted, supplied, water_supplied_liters,
                    compliant_count, anomalous_count,
                    household_count, achieved_fhtc_count, planned_fhtc_count, is_supply_efficient,
                    outage_reason_code, non_submission_reason_code, scheme_status_code,
                    norm_required_lpcd, norm_persons_per_household, norm_over_supply_pct, norm_under_supply_pct,
                    computed_at, is_final
                )
                SELECT days.tenant_id, days.scheme_id, days.d,
                       ds.level_1_lgd_id, ds.level_2_lgd_id, ds.level_3_lgd_id, ds.level_4_lgd_id, ds.level_5_lgd_id, ds.level_6_lgd_id,
                       ds.level_1_dept_id, ds.level_2_dept_id, ds.level_3_dept_id, ds.level_4_dept_id, ds.level_5_dept_id, ds.level_6_dept_id,
                       COALESCE(mr.submitted, 0),
                       COALESCE(wq.supplied, 0),
                       COALESCE(wq.water_supplied_liters, 0),
                       COALESCE(mr.compliant_count, 0),
                       COALESCE(mr.anomalous_count, 0),
                       COALESCE(ds.house_hold_count, 0),
                       COALESCE(ds.fhtc_count, 0),
                       COALESCE(ds.planned_fhtc, 0),
                       CASE
                           WHEN norm.required_lpcd IS NOT NULL
                                AND COALESCE(wq.water_supplied_liters, 0) > 0
                                AND COALESCE(wq.water_supplied_liters, 0)::numeric BETWEEN
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
                JOIN (
                    SELECT DISTINCT ON (tenant_id, scheme_id) *
                    FROM analytics_schema.dim_scheme_table
                    ORDER BY tenant_id, scheme_id,
                             COALESCE(fhtc_count, 0) DESC, COALESCE(house_hold_count, 0) DESC, COALESCE(planned_fhtc, 0) DESC
                ) ds
                  ON ds.scheme_id = days.scheme_id AND ds.tenant_id = days.tenant_id
                LEFT JOIN LATERAL (
                    SELECT n.required_lpcd, n.person_count_per_household,
                           n.over_supply_range_percentage, n.under_supply_range_percentage
                    FROM analytics_schema.dim_tenant_water_norm_table n
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
                    water_supplied_liters = EXCLUDED.water_supplied_liters,
                    compliant_count = EXCLUDED.compliant_count, anomalous_count = EXCLUDED.anomalous_count,
                    household_count = EXCLUDED.household_count, achieved_fhtc_count = EXCLUDED.achieved_fhtc_count,
                    planned_fhtc_count = EXCLUDED.planned_fhtc_count, is_supply_efficient = EXCLUDED.is_supply_efficient,
                    outage_reason_code = EXCLUDED.outage_reason_code,
                    non_submission_reason_code = EXCLUDED.non_submission_reason_code,
                    scheme_status_code = EXCLUDED.scheme_status_code,
                    norm_required_lpcd = EXCLUDED.norm_required_lpcd, norm_persons_per_household = EXCLUDED.norm_persons_per_household,
                    norm_over_supply_pct = EXCLUDED.norm_over_supply_pct, norm_under_supply_pct = EXCLUDED.norm_under_supply_pct,
                    computed_at = EXCLUDED.computed_at, is_final = EXCLUDED.is_final
                """;
        return jdbcTemplate.update(sql, from, to, from, to, from, to, from, to);
    }

    // ============================================================
    // Tier 2 — region rollups (both hierarchies, all 6 levels)
    // ============================================================

    /** Roll fact_scheme_daily_table into fact_region_metrics_table for one bucket, across both hierarchies and all levels. */
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

        // s = scheme set per region (from dim_scheme — authoritative, includes inactive / no-activity schemes).
        //     dim_scheme_table holds one row per parent mapping, so schemes are de-duplicated per region
        //     (DISTINCT ON) before counting/summing — a multi-mapped scheme counts once per region node
        //     but still appears under every region it maps to (same semantics as the legacy queries).
        // a = additive activity sums per region: region membership comes from the dim_scheme mapping
        //     rows joined to fact_scheme_daily_table by (tenant, scheme), NOT from the single location
        //     chain stored on the daily row — a multi-mapped scheme's activity must land in every
        //     region it maps to at this level.
        // ps = per-scheme activity (for non-additive continuous / distinct-submitting counts)
        String sql = ("""
                INSERT INTO analytics_schema.fact_region_metrics_table (
                    period_scale, period_start, period_end, tenant_id, hierarchy, region_level, region_id,
                    days_in_range, scheme_count, total_supply_days, total_submission_days,
                    active_scheme_count, inactive_scheme_count, total_water_supplied_liters,
                    total_household_count, total_achieved_fhtc, total_planned_fhtc,
                    supply_days_in_efficient_range, compliant_submission_count, anomalous_submission_count,
                    continuous_scheme_count, critical_scheme_count, distinct_submitting_schemes,
                    average_regularity, reading_submission_rate, avg_water_supply_per_scheme,
                    norm_required_lpcd, norm_persons_per_household, norm_over_supply_pct, norm_under_supply_pct,
                    computed_at, is_final
                )
                SELECT ?, ?, ?, s.tenant_id, ?, ?, s.region_id,
                       ?, s.scheme_count,
                       COALESCE(a.total_supply_days, 0), COALESCE(a.total_submission_days, 0),
                       s.active_scheme_count, s.inactive_scheme_count,
                       COALESCE(a.total_water_supplied_liters, 0),
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
                       a.norm_required_lpcd, a.norm_persons_per_household, a.norm_over_supply_pct, a.norm_under_supply_pct,
                       CURRENT_TIMESTAMP, ?
                FROM (
                    SELECT region_id, tenant_id,
                           COUNT(*) AS scheme_count,
                           SUM(CASE WHEN operating_status > 0 THEN 1 ELSE 0 END) AS active_scheme_count,
                           SUM(CASE WHEN operating_status > 0 THEN 0 ELSE 1 END) AS inactive_scheme_count,
                           SUM(COALESCE(house_hold_count, 0))::bigint AS total_household_count,
                           SUM(COALESCE(fhtc_count, 0))::bigint AS total_achieved_fhtc,
                           SUM(COALESCE(planned_fhtc, 0))::bigint AS total_planned_fhtc
                    FROM (
                        SELECT DISTINCT ON (%1$s, ds.tenant_id, ds.scheme_id)
                               %1$s AS region_id, ds.tenant_id, ds.scheme_id,
                               ds.operating_status, ds.house_hold_count, ds.fhtc_count, ds.planned_fhtc
                        FROM analytics_schema.dim_scheme_table ds
                        WHERE %1$s IS NOT NULL
                        ORDER BY %1$s, ds.tenant_id, ds.scheme_id,
                                 COALESCE(ds.fhtc_count, 0) DESC, COALESCE(ds.house_hold_count, 0) DESC, COALESCE(ds.planned_fhtc, 0) DESC
                    ) dedup
                    GROUP BY region_id, tenant_id
                ) s
                LEFT JOIN (
                    SELECT m.region_id, sd.tenant_id,
                           SUM(sd.supplied) AS total_supply_days,
                           SUM(sd.submitted) AS total_submission_days,
                           SUM(sd.water_supplied_liters) AS total_water_supplied_liters,
                           SUM(sd.is_supply_efficient) AS supply_days_in_efficient_range,
                           SUM(sd.compliant_count) AS compliant_submission_count,
                           SUM(sd.anomalous_count) AS anomalous_submission_count,
                           MAX(sd.norm_required_lpcd) AS norm_required_lpcd,
                           MAX(sd.norm_persons_per_household) AS norm_persons_per_household,
                           MAX(sd.norm_over_supply_pct) AS norm_over_supply_pct,
                           MAX(sd.norm_under_supply_pct) AS norm_under_supply_pct
                    FROM (
                        SELECT DISTINCT %1$s AS region_id, tenant_id, scheme_id
                        FROM analytics_schema.dim_scheme_table
                        WHERE %1$s IS NOT NULL
                    ) m
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                    WHERE sd.reading_date BETWEEN ? AND ?
                    GROUP BY m.region_id, sd.tenant_id
                ) a ON a.region_id = s.region_id AND a.tenant_id = s.tenant_id
                LEFT JOIN (
                    SELECT region_id, tenant_id,
                           COUNT(*) FILTER (WHERE supply_days = ?) AS continuous_scheme_count,
                           COUNT(*) FILTER (WHERE submission_days > 0) AS distinct_submitting_schemes,
                           COUNT(*) FILTER (WHERE submission_days = 0) AS never_submitted_scheme_count
                    FROM (
                        SELECT m.region_id, sd.tenant_id, sd.scheme_id,
                               SUM(sd.supplied) AS supply_days,
                               SUM(sd.submitted) AS submission_days
                        FROM (
                            SELECT DISTINCT %1$s AS region_id, tenant_id, scheme_id
                            FROM analytics_schema.dim_scheme_table
                            WHERE %1$s IS NOT NULL
                        ) m
                        JOIN analytics_schema.fact_scheme_daily_table sd
                          ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                        WHERE sd.reading_date BETWEEN ? AND ?
                        GROUP BY m.region_id, sd.tenant_id, sd.scheme_id
                    ) ps
                    GROUP BY region_id, tenant_id
                ) c ON c.region_id = s.region_id AND c.tenant_id = s.tenant_id
                ON CONFLICT (period_scale, period_start, tenant_id, hierarchy, region_level, region_id) DO UPDATE SET
                    period_end = EXCLUDED.period_end,
                    days_in_range = EXCLUDED.days_in_range, scheme_count = EXCLUDED.scheme_count,
                    total_supply_days = EXCLUDED.total_supply_days, total_submission_days = EXCLUDED.total_submission_days,
                    active_scheme_count = EXCLUDED.active_scheme_count, inactive_scheme_count = EXCLUDED.inactive_scheme_count,
                    total_water_supplied_liters = EXCLUDED.total_water_supplied_liters,
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
                    norm_required_lpcd = EXCLUDED.norm_required_lpcd, norm_persons_per_household = EXCLUDED.norm_persons_per_household,
                    norm_over_supply_pct = EXCLUDED.norm_over_supply_pct, norm_under_supply_pct = EXCLUDED.norm_under_supply_pct,
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

    /** Roll fact_scheme_daily_table reason/status columns into the long-format distribution table for one bucket. */
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
        // Region membership comes from the dim_scheme mapping rows (a multi-mapped scheme
        // belongs to every region it maps to), not from the daily row's single stored chain.
        String sql = ("""
                INSERT INTO analytics_schema.fact_region_distribution_table (
                    period_scale, period_start, period_end, tenant_id, hierarchy, region_level, region_id,
                    dist_type, dist_key, scheme_count, computed_at, is_final
                )
                SELECT ?, ?, ?, x.tenant_id, ?, ?, x.region_id, x.dist_type, x.dist_key,
                       COUNT(DISTINCT x.scheme_id), CURRENT_TIMESTAMP, ?
                FROM (
                    SELECT m.region_id, sd.tenant_id, sd.scheme_id,
                           'OUTAGE_REASON' AS dist_type, sd.outage_reason_code AS dist_key
                    FROM (
                        SELECT DISTINCT %1$s AS region_id, tenant_id, scheme_id
                        FROM analytics_schema.dim_scheme_table
                        WHERE %1$s IS NOT NULL
                    ) m
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                    WHERE sd.reading_date BETWEEN ? AND ? AND sd.outage_reason_code IS NOT NULL
                    UNION ALL
                    SELECT m.region_id, sd.tenant_id, sd.scheme_id,
                           'NON_SUBMISSION_REASON' AS dist_type, sd.non_submission_reason_code AS dist_key
                    FROM (
                        SELECT DISTINCT %1$s AS region_id, tenant_id, scheme_id
                        FROM analytics_schema.dim_scheme_table
                        WHERE %1$s IS NOT NULL
                    ) m
                    JOIN analytics_schema.fact_scheme_daily_table sd
                      ON sd.tenant_id = m.tenant_id AND sd.scheme_id = m.scheme_id
                    WHERE sd.reading_date BETWEEN ? AND ? AND sd.non_submission_reason_code IS NOT NULL
                ) x
                GROUP BY x.tenant_id, x.region_id, x.dist_type, x.dist_key
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
                INSERT INTO analytics_schema.fact_submission_activity_hourly_table (
                    hour_start, tenant_id, hierarchy, region_level, region_id,
                    submission_count, distinct_scheme_count, computed_at
                )
                SELECT date_trunc('hour', fmr.reading_at) AS hour_start,
                       fmr.tenant_id, 'LGD', 1, ds.level_1_lgd_id,
                       COUNT(*), COUNT(DISTINCT fmr.scheme_id), CURRENT_TIMESTAMP
                FROM analytics_schema.fact_meter_reading_table fmr
                JOIN (
                    -- dim_scheme_table holds one row per parent mapping; dedup so each
                    -- reading is counted once (level_1 is unique per scheme within a tenant).
                    SELECT DISTINCT tenant_id, scheme_id, level_1_lgd_id
                    FROM analytics_schema.dim_scheme_table
                    WHERE level_1_lgd_id IS NOT NULL
                ) ds
                  ON ds.scheme_id = fmr.scheme_id AND ds.tenant_id = fmr.tenant_id
                WHERE fmr.reading_at >= ? AND fmr.reading_at < ?
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
