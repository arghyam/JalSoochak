package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.helper.DashboardWorkStatusFilter;
import org.arghyam.jalsoochak.analytics.helper.WaterSqlFragments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Officer-scoped KPI queries for the Daily Water Service Situation Report (Section Officers).
 *
 * <p>All queries operate on {@code analytics_schema} only and are scoped to one Section Officer's
 * handed-over schemes. Supply is measured with the canonical {@link WaterSqlFragments} definitions
 * shared with the dashboards, and the handed-over filter ({@code {{WS}}}) is applied to
 * <em>every</em> query — the template's "Total Schemes (Handed Over Schemes only)" is the
 * denominator of the household percentages on the same page, so the numerator must be drawn from
 * the same set.</p>
 *
 * <p>There is no supervisor narrowing here: only Section Officers receive a daily report, and it
 * carries no per-officer breakdown. Sub-Divisional Officers are served by the weekly report
 * ({@link WeeklySituationReportRepository}), which does narrow.</p>
 */
@Repository
public class DailySituationReportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DashboardWorkStatusFilter workStatusFilter;

    public DailySituationReportRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${analytics.dashboard.included-work-statuses:4}") String includedWorkStatusesCsv) {
        this.jdbcTemplate = jdbcTemplate;
        this.workStatusFilter = new DashboardWorkStatusFilter(includedWorkStatusesCsv);
    }

    /**
     * The officer's handed-over schemes collapsed to one row each, carrying the household count and
     * whether the scheme supplied water on {@code day}.
     *
     * <p>One query answers most of the Summary section — total schemes, schemes supplying, schemes
     * not supplying, households with and without supply, and the population behind Average LPCD —
     * plus the Section 2 list of schemes with no supply. Deriving them from a single result set is
     * not only cheaper than six round trips; it makes them arithmetically consistent by construction,
     * which separate queries against a table still being written to at 16:00 would not be.</p>
     *
     * <p>{@code DISTINCT ON} collapses the scheme fan-out (one {@code dim_scheme_table} row per
     * LGD/department mapping) before {@code fhtc_count} is read, so a scheme spanning three villages
     * contributes its households once rather than three times.</p>
     */
    public List<SchemeDaySnapshot> listSchemeDaySnapshots(Integer tenantId, Long userId, LocalDate day) {
        String sql = WaterSqlFragments.withWaterFragments("""
                WITH user_schemes AS (
                    SELECT DISTINCT ON (s.tenant_id, s.scheme_id)
                           s.scheme_id,
                           COALESCE(s.fhtc_count, 0)::bigint AS fhtc
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id AND s.tenant_id = usm.tenant_id
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?{{WS}}
                    ORDER BY s.tenant_id, s.scheme_id, {{CSA}}
                ),
                supply AS (
                    SELECT f.scheme_id
                    FROM {{LWQ}} f
                    WHERE f.tenant_id = ?
                      AND f.date = ?
                      AND {{SWD}}
                    GROUP BY f.scheme_id
                )
                SELECT us.scheme_id,
                       us.fhtc,
                       (sp.scheme_id IS NOT NULL) AS supplied
                FROM user_schemes us
                LEFT JOIN supply sp ON sp.scheme_id = us.scheme_id
                ORDER BY us.scheme_id
                """
                .replace("{{WS}}", workStatusFilter.andPredicate("s"))
                .replace("{{CSA}}", WaterSqlFragments.schemeAttributeRowOrder("s")));

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeDaySnapshot(
                        rs.getInt("scheme_id"),
                        rs.getLong("fhtc"),
                        rs.getBoolean("supplied")),
                userId, tenantId, tenantId, day);
    }

    /**
     * One of the officer's handed-over schemes on the report day.
     *
     * @param schemeId scheme surrogate id, the same value in {@code dim_scheme_table.scheme_id} and
     *                 the operational {@code scheme_master_table.id}
     * @param fhtc     achieved functional household tap connections, de-duplicated across mappings
     * @param supplied whether the scheme supplied water that day, per the canonical definition
     */
    public record SchemeDaySnapshot(int schemeId, long fhtc, boolean supplied) {
    }

    /**
     * Litres supplied across the officer's handed-over schemes on {@code day} — the Average LPCD
     * numerator. Non-supplying schemes contribute zero, so this is the same figure whether taken over
     * all the officer's schemes or only the supplying ones; it is the <em>denominator</em> that
     * narrows to the supplying subset.
     */
    public long sumWaterSuppliedOnDay(Integer tenantId, Long userId, LocalDate day) {
        String sql = WaterSqlFragments.withWaterFragments("""
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id AND s.tenant_id = usm.tenant_id
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?{{WS}}
                )
                SELECT {{SWS}} AS litres_supplied
                FROM {{LWQ}} f
                JOIN user_schemes us ON us.scheme_id = f.scheme_id
                WHERE f.tenant_id = ?
                  AND f.date = ?
                """
                .replace("{{WS}}", workStatusFilter.andPredicate("s")));

        Long value = jdbcTemplate.queryForObject(sql, Long.class, userId, tenantId, tenantId, day);
        return value != null ? value : 0L;
    }

    /**
     * Total anomalies raised against the officer's handed-over schemes in the half-open interval
     * {@code [fromInclusive, toExclusive)} of {@code created_at}.
     *
     * <p>{@code anomaly_table.created_at} is UTC-naive while the report's day is an IST calendar day,
     * so the caller converts the window before binding it. Counts {@code DISTINCT a.id} and excludes
     * soft-deleted rows.</p>
     */
    public int countAnomalies(Integer tenantId, Long userId,
                              LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        String sql = WaterSqlFragments.withWaterFragments("""
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id AND s.tenant_id = usm.tenant_id
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?{{WS}}
                )
                SELECT COUNT(DISTINCT a.id)::int
                FROM analytics_schema.anomaly_table a
                JOIN user_schemes us ON us.scheme_id = a.scheme_id
                WHERE a.tenant_id = ?
                  AND a.deleted_at IS NULL
                  AND a.created_at >= ?
                  AND a.created_at < ?
                """
                .replace("{{WS}}", workStatusFilter.andPredicate("s")));

        Integer value = jdbcTemplate.queryForObject(sql, Integer.class,
                userId, tenantId, tenantId, fromInclusive, toExclusive);
        return value != null ? value : 0;
    }

    /**
     * One row per (scheme, anomaly type) raised against the officer's handed-over schemes in the
     * window — the Section 3 table. Scheme name, IMIS id and Jal Mitra contact are resolved
     * downstream in message-service, which holds the operational schema and the PII.
     *
     * <p>{@code type} is the anomaly enum name; rows written before analytics migration V29 carry the
     * numeric code as a string instead, and the downstream label mapping handles both. The section is
     * data-driven, so a newly introduced anomaly type appears without a change here.</p>
     */
    public List<SchemeAnomaly> listAnomaliesByScheme(Integer tenantId, Long userId,
                                                     LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        String sql = WaterSqlFragments.withWaterFragments("""
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id AND s.tenant_id = usm.tenant_id
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?{{WS}}
                )
                SELECT DISTINCT a.scheme_id, a.type
                FROM analytics_schema.anomaly_table a
                JOIN user_schemes us ON us.scheme_id = a.scheme_id
                WHERE a.tenant_id = ?
                  AND a.deleted_at IS NULL
                  AND a.created_at >= ?
                  AND a.created_at < ?
                ORDER BY a.scheme_id, a.type
                """
                .replace("{{WS}}", workStatusFilter.andPredicate("s")));

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeAnomaly(rs.getInt("scheme_id"), rs.getString("type")),
                userId, tenantId, tenantId, fromInclusive, toExclusive);
    }

    /** One anomaly type observed on one scheme during the report window. */
    public record SchemeAnomaly(int schemeId, String type) {
    }

    /**
     * Anomaly counts grouped by {@code type} for the officer's handed-over schemes over the same
     * window. Retained for callers that want the breakdown without the per-scheme detail.
     */
    public List<DailyReportKpiDTO.TypeCount> countAnomaliesByType(
            Integer tenantId, Long userId, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        String sql = WaterSqlFragments.withWaterFragments("""
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id AND s.tenant_id = usm.tenant_id
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?{{WS}}
                )
                SELECT a.type AS type,
                       COUNT(DISTINCT a.id)::int AS cnt
                FROM analytics_schema.anomaly_table a
                JOIN user_schemes us ON us.scheme_id = a.scheme_id
                WHERE a.tenant_id = ?
                  AND a.deleted_at IS NULL
                  AND a.created_at >= ?
                  AND a.created_at < ?
                GROUP BY a.type
                ORDER BY a.type
                """
                .replace("{{WS}}", workStatusFilter.andPredicate("s")));

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> DailyReportKpiDTO.TypeCount.builder()
                        .type(rs.getString("type"))
                        .count(rs.getInt("cnt"))
                        .build(),
                userId, tenantId, tenantId, fromInclusive, toExclusive);
    }
}
