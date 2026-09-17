package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.helper.DashboardWorkStatusFilter;
import org.arghyam.jalsoochak.analytics.helper.WaterSqlFragments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Officer-scoped queries for the Weekly Water Service Situation Report, serving both the Section
 * Officer and Sub-Divisional Officer variants.
 *
 * <p>The whole report rests on <em>one</em> query: per scheme, how many days it supplied water during
 * the week, how many litres it supplied, and how many households it serves
 * ({@link #listSchemeWeekSnapshots}). Every KPI and every scheme list in both variants is a filter or
 * a sum over that one result set, run once for the reported week and once for the comparison week.
 * Expressing the report this way is what keeps its rows and its totals in agreement — a report whose
 * "Schemes Not Supplying" count came from a different query than its "Schemes with no supply" list
 * could disagree with itself.</p>
 *
 * <p>The handed-over filter ({@code {{WS}}}) applies throughout, and supply uses the canonical
 * {@link WaterSqlFragments} definitions shared with the dashboards.</p>
 */
@Repository
public class WeeklySituationReportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DashboardWorkStatusFilter workStatusFilter;

    public WeeklySituationReportRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${analytics.dashboard.included-work-statuses:4}") String includedWorkStatusesCsv) {
        this.jdbcTemplate = jdbcTemplate;
        this.workStatusFilter = new DashboardWorkStatusFilter(includedWorkStatusesCsv);
    }

    /**
     * Per-scheme supply days, litres and household count for one officer over {@code [start, end]}.
     *
     * <p>A scheme mapped to the officer that supplied on no day of the week still appears, with
     * {@code supplyDays = 0} and {@code litres = 0} — that absence is the report's Section 2, so it
     * must be a row rather than a missing one.</p>
     *
     * <p>{@code supervisorUserId} narrows the officer's schemes to those <em>also</em> mapped to that
     * supervising officer. It drives the SDO report's per-Section-Officer table, where a Section
     * Officer must contribute only the schemes they share with that SDO — so an SO's own report
     * legitimately covers more schemes than their row in their SDO's. A {@code null} disables the
     * predicate, leaving the SO's own report and the SDO's own totals unnarrowed. The id is bound,
     * never concatenated; the explicit casts let PostgreSQL infer the type when the bind is null.</p>
     *
     * @param start first day of the week, inclusive
     * @param end   last day of the week, inclusive
     */
    public List<SchemeWeekSnapshot> listSchemeWeekSnapshots(
            Integer tenantId, Long userId, LocalDate start, LocalDate end, Long supervisorUserId) {
        String sql = WaterSqlFragments.withWaterFragments("""
                WITH user_schemes AS (
                    SELECT DISTINCT ON (s.tenant_id, s.scheme_id)
                           s.scheme_id,
                           COALESCE(s.fhtc_count, 0)::bigint AS fhtc
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    JOIN analytics_schema.dim_scheme_table s
                        ON s.scheme_id = usm.scheme_id AND s.tenant_id = usm.tenant_id
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id)){{WS}}
                    ORDER BY s.tenant_id, s.scheme_id, {{CSA}}
                ),
                supply AS (
                    SELECT f.scheme_id,
                           COUNT(DISTINCT f.date)::int AS supply_days,
                           {{SWS}} AS litres
                    FROM {{LWQ}} f
                    JOIN user_schemes us ON us.scheme_id = f.scheme_id
                    WHERE f.tenant_id = ?
                      AND f.date BETWEEN ? AND ?
                      AND {{SWD}}
                    GROUP BY f.scheme_id
                )
                SELECT us.scheme_id,
                       us.fhtc,
                       COALESCE(sp.supply_days, 0)::int AS supply_days,
                       COALESCE(sp.litres, 0)::bigint AS litres
                FROM user_schemes us
                LEFT JOIN supply sp ON sp.scheme_id = us.scheme_id
                ORDER BY us.scheme_id
                """
                .replace("{{WS}}", workStatusFilter.andPredicate("s"))
                .replace("{{CSA}}", WaterSqlFragments.schemeAttributeRowOrder("s")));

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SchemeWeekSnapshot(
                        rs.getInt("scheme_id"),
                        rs.getLong("fhtc"),
                        rs.getInt("supply_days"),
                        rs.getLong("litres")),
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, start, end);
    }

    /**
     * One of the officer's handed-over schemes over the reported week.
     *
     * @param schemeId   scheme surrogate id, the same value as the operational {@code scheme_master_table.id}
     * @param fhtc       achieved functional household tap connections, de-duplicated across mappings
     * @param supplyDays distinct days in the week the scheme supplied water (0–7)
     * @param litres     litres supplied across the week
     */
    public record SchemeWeekSnapshot(int schemeId, long fhtc, int supplyDays, long litres) {
    }
}
