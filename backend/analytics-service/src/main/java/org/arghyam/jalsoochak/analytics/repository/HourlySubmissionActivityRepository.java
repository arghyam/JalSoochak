package org.arghyam.jalsoochak.analytics.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Reads hourly reading-submission activity on the fly from the base fact
 * ({@code fact_meter_reading_table}), for any region at any hierarchy level.
 *
 * <p>This is the drill-down path. It needs no pre-rolled hourly rows per region — which
 * would multiply storage and per-hour churn across all six levels — because a single
 * region's activity over a short date range is a small, indexed scan. The pre-rolled
 * {@code fact_submission_activity_hourly_table} stays as the cross-tenant national fast
 * path only.</p>
 *
 * <p>Counts are per hour: {@code submission_count} is additive; {@code distinct_scheme_count}
 * is a per-hour figure and must NOT be summed across hours (a scheme can submit in several
 * hours). Both match the semantics of the existing hourly rollup, which is <em>unfiltered</em>
 * by {@code work_status} — activity is "when did readings arrive", so every reading counts.</p>
 *
 * <p>Region membership comes from {@code dim_scheme_table} mapping rows (a multi-mapped
 * scheme belongs to every region it maps to), de-duplicated per scheme so a reading is
 * counted once. {@code hierarchy} is a controlled {@code LGD}/{@code DEPT} value (never
 * user text), so the level-column names it selects are not an injection vector.</p>
 */
@Repository
public class HourlySubmissionActivityRepository {

    private final JdbcTemplate jdbcTemplate;

    public HourlySubmissionActivityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One hour bucket of submission activity. */
    public record HourlyActivityRow(LocalDateTime hourStart, long submissionCount, int distinctSchemeCount) {
    }

    private static final RowMapper<HourlyActivityRow> HOURLY_MAPPER = (rs, n) -> new HourlyActivityRow(
            rs.getTimestamp("hour_start").toLocalDateTime(),
            rs.getLong("submission_count"),
            rs.getInt("distinct_scheme_count"));

    /** Tenant-wide hourly activity over [start, end] (all of the tenant's schemes). */
    public List<HourlyActivityRow> getTenantHourly(int tenantId, LocalDate start, LocalDate end) {
        String sql = """
                SELECT date_trunc('hour', reading_at) AS hour_start,
                       COUNT(*)                        AS submission_count,
                       COUNT(DISTINCT scheme_id)       AS distinct_scheme_count
                FROM analytics_schema.fact_meter_reading_table
                WHERE tenant_id = ? AND reading_date BETWEEN ? AND ?
                GROUP BY date_trunc('hour', reading_at)
                ORDER BY hour_start
                """;
        return jdbcTemplate.query(sql, HOURLY_MAPPER, tenantId, start, end);
    }

    /**
     * Region-scoped hourly activity over [start, end]. {@code hierarchy} = {@code LGD}|{@code DEPT}
     * selects the ancestor columns; a region id matches exactly one level, so OR-ing the six level
     * columns selects the schemes under that node without needing to know its level.
     */
    public List<HourlyActivityRow> getRegionHourly(int tenantId, String hierarchy, int regionId,
                                                   LocalDate start, LocalDate end) {
        String orClause = regionMembershipOrClause(hierarchy);
        String sql = ("""
                SELECT date_trunc('hour', fmr.reading_at) AS hour_start,
                       COUNT(*)                            AS submission_count,
                       COUNT(DISTINCT fmr.scheme_id)       AS distinct_scheme_count
                FROM (
                    SELECT DISTINCT ds.tenant_id, ds.scheme_id
                    FROM analytics_schema.dim_scheme_table ds
                    WHERE ds.tenant_id = ? AND (%s)
                ) m
                JOIN analytics_schema.fact_meter_reading_table fmr
                  ON fmr.tenant_id = m.tenant_id AND fmr.scheme_id = m.scheme_id
                WHERE fmr.reading_date BETWEEN ? AND ?
                GROUP BY date_trunc('hour', fmr.reading_at)
                ORDER BY hour_start
                """).formatted(orClause);

        Object[] params = new Object[1 + 6 + 2];
        params[0] = tenantId;
        for (int i = 0; i < 6; i++) {
            params[1 + i] = regionId;
        }
        params[7] = start;
        params[8] = end;
        return jdbcTemplate.query(sql, HOURLY_MAPPER, params);
    }

    /** OR over the six level columns for {@code hierarchy}: a region id matches exactly one level. */
    private static String regionMembershipOrClause(String hierarchy) {
        String suffix = "DEPT".equalsIgnoreCase(hierarchy) ? "dept" : "lgd";
        return IntStream.rangeClosed(1, 6)
                .mapToObj(i -> "ds.level_" + i + "_" + suffix + "_id = ?")
                .collect(Collectors.joining(" OR "));
    }
}
