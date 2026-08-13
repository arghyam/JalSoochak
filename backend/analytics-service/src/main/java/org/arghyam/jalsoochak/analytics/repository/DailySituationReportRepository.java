package org.arghyam.jalsoochak.analytics.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Officer-scoped KPI queries for the Daily Water Service Situation Report.
 *
 * <p>All queries operate on {@code analytics_schema} only and are scoped to a single officer's
 * schemes via {@code dim_user_scheme_mapping_table}. They follow the same CTE patterns already
 * used in {@link SchemeRegularityRepository} (supply-day / submission-day aggregation over
 * {@code fact_meter_reading_table}). All runtime values are bound as {@code ?} parameters.</p>
 *
 * <p>Every method takes an optional {@code supervisorUserId}. When non-null, the officer's schemes
 * are additionally narrowed to those <em>also</em> mapped to that supervising officer — the SDO
 * report's per-Section-Officer breakdown, where a Section Officer must only contribute the schemes
 * they share with the SDO. When null (the officer's own Summary section) the behaviour is unchanged.
 * The restriction is expressed inside the {@code user_schemes} CTE as</p>
 *
 * <pre>{@code AND (CAST(? AS bigint) IS NULL OR EXISTS (... sup.user_id = CAST(? AS bigint) ...))}</pre>
 *
 * <p>so the SQL stays a single static text block — the supervisor id is a bind parameter, never
 * concatenated, and a null bind disables the predicate. The explicit casts are required for
 * PostgreSQL to infer the parameter type when the bound value is {@code null}.</p>
 */
@Repository
@RequiredArgsConstructor
public class DailySituationReportRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Number of the officer's schemes that supplied water on {@code day}
     * (any reading with {@code confirmed_reading > 0}).
     */
    public int countSchemesSupplyingOnDay(Integer tenantId, Long userId, LocalDate day, Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
                ),
                scheme_day AS (
                    SELECT m.scheme_id,
                           MAX(CASE WHEN m.confirmed_reading > 0 THEN 1 ELSE 0 END) AS has_supply
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date = ?
                    GROUP BY m.scheme_id
                )
                SELECT COALESCE(SUM(has_supply), 0)::int FROM scheme_day
                """;
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class,
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, day);
        return value != null ? value : 0;
    }

    /**
     * Number of the officer's schemes that submitted at least one reading on {@code day}
     * ({@code confirmed_reading >= 0}). Numerator for the reading-submission percentage.
     */
    public int countSchemesSubmittingOnDay(Integer tenantId, Long userId, LocalDate day, Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
                ),
                scheme_day AS (
                    SELECT m.scheme_id,
                           MAX(CASE WHEN m.confirmed_reading >= 0 THEN 1 ELSE 0 END) AS has_submission
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date = ?
                    GROUP BY m.scheme_id
                )
                SELECT COALESCE(SUM(has_submission), 0)::int FROM scheme_day
                """;
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class,
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, day);
        return value != null ? value : 0;
    }

    /**
     * Total supply-days across the officer's schemes over {@code [start, end]} inclusive.
     * A scheme-day counts once when any reading that day has {@code confirmed_reading > 0}.
     * Divide by {@code schemeCount * daysInRange} for the regular-supply percentage.
     */
    public int sumSupplyDaysInRange(Integer tenantId, Long userId, LocalDate start, LocalDate end,
                                    Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
                ),
                scheme_day AS (
                    SELECT m.scheme_id,
                           m.reading_date::date AS reading_date,
                           MAX(CASE WHEN m.confirmed_reading > 0 THEN 1 ELSE 0 END) AS has_supply
                    FROM analytics_schema.fact_meter_reading_table m
                    JOIN user_schemes us ON us.scheme_id = m.scheme_id
                    WHERE m.tenant_id = ?
                      AND m.reading_date BETWEEN ? AND ?
                    GROUP BY m.scheme_id, m.reading_date::date
                )
                SELECT COALESCE(COUNT(*) FILTER (WHERE has_supply = 1), 0)::int FROM scheme_day
                """;
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class,
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, start, end);
        return value != null ? value : 0;
    }

    /**
     * Total litres supplied across the officer's schemes on {@code day}
     * (sum of {@code fact_water_quantity_table.water_quantity}). Basis for MLD and LPCD.
     */
    public long sumWaterSuppliedOnDay(Integer tenantId, Long userId, LocalDate day, Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
                )
                SELECT COALESCE(SUM(f.water_quantity), 0)::bigint
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us ON us.scheme_id = f.scheme_id
                WHERE f.tenant_id = ?
                  AND f.date = ?
                """;
        Long value = jdbcTemplate.queryForObject(sql, Long.class,
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, day);
        return value != null ? value : 0L;
    }

    /**
     * Population served by the officer's schemes, modelled as
     * {@code SUM(fhtc_count) * person_count_per_household} — the same population basis the existing
     * LPCD/efficiency-range logic in {@link SchemeRegularityRepository} uses. Returns 0 when unknown.
     */
    public long populationServed(Integer tenantId, Long userId, Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
                )
                SELECT COALESCE(SUM(s.fhtc_count), 0)::bigint
                     * COALESCE((SELECT person_count_per_household
                                 FROM analytics_schema.dim_tenant_table WHERE tenant_id = ?), 5)
                FROM analytics_schema.dim_scheme_table s
                JOIN user_schemes us ON us.scheme_id = s.scheme_id
                WHERE s.tenant_id = ?
                """;
        Long value = jdbcTemplate.queryForObject(sql, Long.class,
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, tenantId);
        return value != null ? value : 0L;
    }

    /**
     * Per-scheme "no water supply" rows for the officer on {@code day} — the detail behind the
     * outage-reason aggregate, used to build the Priority Actions table. For each of the officer's
     * schemes that recorded an {@code outage_reason} on {@code day}, returns the scheme id, the outage
     * reason, and the last date the scheme actually supplied water (any {@code confirmed_reading > 0}),
     * from which the caller derives "no supply for N days". {@code lastSupplyDate} is null if the scheme
     * has never supplied. Scheme name / IMIS id / operators are resolved downstream (message-service).
     */
    public List<NoSupplyScheme> listNoSupplyByScheme(Integer tenantId, Long userId, LocalDate day,
                                                     Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
                )
                SELECT f.scheme_id,
                       MIN(f.outage_reason) AS outage_reason,
                       (SELECT MAX(m.reading_date)
                          FROM analytics_schema.fact_meter_reading_table m
                         WHERE m.scheme_id = f.scheme_id
                           AND m.tenant_id = ?
                           AND m.confirmed_reading > 0
                           AND m.reading_date <= ?) AS last_supply_date
                FROM analytics_schema.fact_water_quantity_table f
                JOIN user_schemes us ON us.scheme_id = f.scheme_id
                WHERE f.tenant_id = ?
                  AND f.date = ?
                  AND f.outage_reason IS NOT NULL
                GROUP BY f.scheme_id
                ORDER BY f.scheme_id
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NoSupplyScheme(
                        rs.getInt("scheme_id"),
                        rs.getString("outage_reason"),
                        rs.getObject("last_supply_date", LocalDate.class)),
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, day, tenantId, day);
    }

    /** One officer scheme that reported an outage on the report day, plus its last supply date. */
    public record NoSupplyScheme(int schemeId, String outageReason, LocalDate lastSupplyDate) {
    }

    /**
     * Anomaly counts grouped by {@code type} for the officer's schemes over the half-open
     * interval {@code [fromInclusive, toExclusive)} of {@code created_at} (UTC-naive, matching
     * how anomaly timestamps are stored). Excludes soft-deleted rows.
     *
     * <p>Selects through the same {@code user_schemes} CTE as the other KPIs rather than joining the
     * mapping table inline; equivalent for the unsupervised case, because {@code a.tenant_id = ?}
     * already pins the anomaly rows to the tenant the CTE is scoped to.</p>
     */
    public List<DailyReportKpiDTO.TypeCount> countAnomaliesByType(
            Integer tenantId, Long userId, LocalDateTime fromInclusive, LocalDateTime toExclusive,
            Long supervisorUserId) {
        String sql = """
                WITH user_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM analytics_schema.dim_user_scheme_mapping_table usm
                    WHERE usm.user_id = ?
                      AND usm.tenant_id = ?
                      AND (CAST(? AS bigint) IS NULL
                           OR EXISTS (SELECT 1
                                        FROM analytics_schema.dim_user_scheme_mapping_table sup
                                       WHERE sup.user_id = CAST(? AS bigint)
                                         AND sup.tenant_id = usm.tenant_id
                                         AND sup.scheme_id = usm.scheme_id))
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
                """;
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> DailyReportKpiDTO.TypeCount.builder()
                        .type(rs.getString("type"))
                        .count(rs.getInt("cnt"))
                        .build(),
                userId, tenantId, supervisorUserId, supervisorUserId, tenantId, fromInclusive, toExclusive);
    }
}
