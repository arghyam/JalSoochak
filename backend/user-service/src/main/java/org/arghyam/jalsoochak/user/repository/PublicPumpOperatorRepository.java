package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.arghyam.jalsoochak.user.util.UserTypeLabel;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All SQL in this repository uses {@code String.format} to inject only pre-validated,
 * internal-only values:
 * <ul>
 *   <li>{@code schemaName} — validated by {@link #validateSchemaName} against
 *       {@code ^[a-z_][a-z0-9_]*$}.</li>
 *   <li>Column name fragments ({@code timeColumn}, {@code confirmedExpr}, {@code schemeJoin}) —
 *       returned from internal helpers that produce only hardcoded SQL literals.</li>
 *   <li>Conditional SQL fragments (filter, ORDER-BY) — assembled from hardcoded string
 *       constants; user input is always bound as a {@code ?} parameter.</li>
 *   <li>IN-clause placeholders — built as {@code "?, ?, ..."} strings from collection size.</li>
 * </ul>
 * No user-supplied data is ever concatenated into any query string.
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
public class PublicPumpOperatorRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService pii;

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        Object o = rs.getObject(column);
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        return Integer.valueOf(o.toString());
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        Object o = rs.getObject(column);
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        return Double.valueOf(o.toString());
    }

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    private boolean columnExists(String schemaName, String tableName, String columnName) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = ?
                      AND column_name = ?
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schemaName, tableName, columnName);
        return Boolean.TRUE.equals(exists);
    }

    private boolean tableExists(String schemaName, String tableName) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = ?
                      AND table_name = ?
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schemaName, tableName);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * flow_reading_table time column differs across tenant schema versions:
     * - legacy: reading_at
     * - newer:  observation_time
     */
    private String resolveFlowReadingTimeColumn(String schemaName) {
        return columnExists(schemaName, "flow_reading_table", "observation_time") ? "observation_time" : "reading_at";
    }

    private String resolveConfirmedReadingExpression(String schemaName, String tableAlias) {
        if (columnExists(schemaName, "flow_reading_table", "payload_json")) {
            return String.format(
                    "COALESCE(NULLIF(%s.confirmed_reading, 0), (%s.payload_json ->> 'confirmed_reading')::numeric, %s.confirmed_reading)",
                    tableAlias,
                    tableAlias,
                    tableAlias
            );
        }
        return tableAlias + ".confirmed_reading";
    }

    public PumpOperatorDetailsDTO findPumpOperatorById(
            String schemaName,
            long pumpOperatorId,
            Long schemeId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String schemeJoin;
        String schemeFilterSql = "";
        String schemeRequiredSql = "";
        List<Object> params = new ArrayList<>();
        if (tableExists(schemaName, "user_scheme_mapping_table")) {
            if (schemeId != null) {
                schemeFilterSql = "\n  AND usm.scheme_id = ?";
                schemeRequiredSql = "\n  AND sch.scheme_id IS NOT NULL";
                params.add(schemeId);
            }
            schemeJoin = String.format("""
                    LEFT JOIN LATERAL (
                        SELECT sm.id AS scheme_id,
                               sm.state_scheme_id,
                               sm.centre_scheme_id,
                               sm.scheme_name,
                               sm.latitude,
                               sm.longitude
                        FROM %s.user_scheme_mapping_table usm
                        JOIN %s.scheme_master_table sm
                          ON sm.id = usm.scheme_id
                         AND sm.deleted_at IS NULL
                        WHERE usm.deleted_at IS NULL
                          AND usm.user_id = u.id
                          AND usm.status = 1
                          %s
                        ORDER BY usm.id DESC
                        LIMIT 1
                    ) sch ON true
                    """, schemaName, schemaName, schemeFilterSql);
        } else {
            schemeJoin = """
                    LEFT JOIN LATERAL (
                        SELECT NULL::integer AS scheme_id,
                               NULL::text AS state_scheme_id,
                               NULL::text AS centre_scheme_id,
                               NULL::text AS scheme_name,
                               NULL::double precision AS latitude,
                               NULL::double precision AS longitude
                    ) sch ON true
                    """;
        }
        String sql = String.format("""
                SELECT u.id,
                       u.uuid,
                       u.title,
                       u.email,
                       u.phone_number,
                       u.status,
                       u.created_at::date AS onboarding_date,
                       ut.c_name AS role,
                       sch.scheme_id,
                       sch.state_scheme_id,
                       sch.centre_scheme_id,
                       sch.scheme_name,
                       sch.latitude AS scheme_latitude,
                       sch.longitude AS scheme_longitude,
                       rs.last_submission_at,
                       rs.first_submission_date,
                       comp.total_days_since_first_submission,
                       rs.submitted_days,
                       comp.reporting_rate_percent,
                       comp.missed_submission_days
                FROM %s.user_table u
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                %s
                LEFT JOIN LATERAL (
                    SELECT
                        MAX(fr.%s) AS last_submission_at,
                        MIN(fr.reading_date) AS first_submission_date,
                        COUNT(DISTINCT fr.reading_date) AS submitted_days
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = u.id
                      AND fr.reading_date >= COALESCE(CAST(? AS date), fr.reading_date)
                      AND fr.reading_date <= COALESCE(CAST(? AS date), fr.reading_date)
                ) rs ON true
                LEFT JOIN LATERAL (
                    WITH bounds AS (
                        WITH requested AS (
                            SELECT
                                CAST(? AS date) AS requested_start_date,
                                CAST(? AS date) AS requested_end_date
                        )
                        SELECT
                            GREATEST(
                                u.created_at::date,
                                COALESCE(requested.requested_start_date, u.created_at::date)
                            ) AS start_date,
                            LEAST(CURRENT_DATE, COALESCE(requested.requested_end_date, CURRENT_DATE)) AS end_date
                        FROM requested
                        WHERE u.created_at IS NOT NULL
                          AND GREATEST(
                                u.created_at::date,
                                COALESCE(requested.requested_start_date, u.created_at::date)
                              )
                              <= LEAST(CURRENT_DATE, COALESCE(requested.requested_end_date, CURRENT_DATE))
                    ),
                    days AS (
                        SELECT (bounds.start_date + gs) AS d
                        FROM bounds
                        JOIN generate_series(0, (bounds.end_date - bounds.start_date)) gs ON true
                    ),
                    reported AS (
                        SELECT DISTINCT fr.reading_date AS d
                        FROM %s.flow_reading_table fr
                        JOIN bounds ON true
                        WHERE fr.deleted_at IS NULL
                          AND fr.created_by = u.id
                          AND fr.reading_date BETWEEN bounds.start_date AND bounds.end_date
                    )
                    SELECT
                        (bounds.end_date - bounds.start_date + 1) AS total_days_since_first_submission,
                        ROUND(
                            (rs.submitted_days::numeric * 100.0) / NULLIF((bounds.end_date - bounds.start_date + 1), 0),
                            2
                        ) AS reporting_rate_percent,
                        (
                            SELECT array_agg(days.d ORDER BY days.d)
                            FROM days
                            LEFT JOIN reported ON reported.d = days.d
                            WHERE reported.d IS NULL
                        ) AS missed_submission_days
                    FROM bounds
                ) comp ON true
                WHERE u.deleted_at IS NULL
                  AND u.id = ?
                  %s
                  AND upper(COALESCE(ut.c_name, '')) = 'PUMP_OPERATOR'
                LIMIT 1
                """, schemaName, schemeJoin, timeColumn, schemaName, schemaName, schemeRequiredSql);
        try {
            params.add(startDate);
            params.add(endDate);
            params.add(startDate);
            params.add(endDate);
            params.add(pumpOperatorId);
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Timestamp lastTs = (Timestamp) rs.getObject("last_submission_at");
                LocalDateTime lastSubmissionAt = lastTs == null ? null : lastTs.toLocalDateTime();

                java.sql.Date firstD = (java.sql.Date) rs.getObject("first_submission_date");
                LocalDate firstSubmissionDate = firstD == null ? null : firstD.toLocalDate();

                Number totalDaysN = (Number) rs.getObject("total_days_since_first_submission");
                Integer totalDays = totalDaysN == null ? null : totalDaysN.intValue();
                Number submittedDaysN = (Number) rs.getObject("submitted_days");
                Integer submittedDays = submittedDaysN == null ? null : submittedDaysN.intValue();

                List<LocalDate> missedDays = null;
                Array missedArr = (Array) rs.getObject("missed_submission_days");
                if (missedArr != null) {
                    Object raw = missedArr.getArray();
                    if (raw instanceof java.sql.Date[] sqlDates) {
                        missedDays = new ArrayList<>(sqlDates.length);
                        for (java.sql.Date d : sqlDates) {
                            missedDays.add(d == null ? null : d.toLocalDate());
                        }
                    } else if (raw instanceof Object[] objs) {
                        missedDays = new ArrayList<>(objs.length);
                        for (Object o : objs) {
                            if (o == null) {
                                missedDays.add(null);
                            } else if (o instanceof java.sql.Date d) {
                                missedDays.add(d.toLocalDate());
                            } else if (o instanceof LocalDate d) {
                                missedDays.add(d);
                            } else {
                                missedDays.add(LocalDate.parse(o.toString()));
                            }
                        }
                    }
                }

                return PumpOperatorDetailsDTO.builder()
                        .id(rs.getLong("id"))
                        .uuid(rs.getString("uuid"))
                        .name(pii.safeDecrypt(rs.getString("title")))
                        .email(rs.getString("email"))
                        .phoneNumber(pii.safeDecrypt(rs.getString("phone_number")))
                        .status(mapStatus(getNullableInt(rs, "status")))
                        .schemeId(getNullableInt(rs, "scheme_id"))
                        .stateSchemeId(rs.getString("state_scheme_id"))
                        .centerSchemeId(rs.getString("centre_scheme_id"))
                        .schemeName(rs.getString("scheme_name"))
                        .schemeLatitude(getNullableDouble(rs, "scheme_latitude"))
                        .schemeLongitude(getNullableDouble(rs, "scheme_longitude"))
                        .lastSubmissionAt(lastSubmissionAt)
                        .firstSubmissionDate(firstSubmissionDate)
                        .totalDaysSinceFirstSubmission(totalDays)
                        .submittedDays(submittedDays)
                        .reportingRatePercent((BigDecimal) rs.getObject("reporting_rate_percent"))
                        .missedSubmissionDays(missedDays)
                        .build();
            }, params.toArray());
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public PumpOperatorDetailsDTO findPumpOperatorById(String schemaName, long pumpOperatorId) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String schemeJoin;
        if (tableExists(schemaName, "user_scheme_mapping_table")) {
            schemeJoin = String.format("""
                    LEFT JOIN LATERAL (
                        SELECT sm.id AS scheme_id,
                               sm.state_scheme_id,
                               sm.centre_scheme_id,
                               sm.scheme_name,
                               sm.latitude,
                               sm.longitude
                        FROM %s.user_scheme_mapping_table usm
                        JOIN %s.scheme_master_table sm
                          ON sm.id = usm.scheme_id
                         AND sm.deleted_at IS NULL
                        WHERE usm.deleted_at IS NULL
                          AND usm.user_id = u.id
                          AND usm.status = 1
                        ORDER BY usm.id DESC
                        LIMIT 1
                    ) sch ON true
                    """, schemaName, schemaName);
        } else {
            schemeJoin = """
                    LEFT JOIN LATERAL (
                        SELECT NULL::integer AS scheme_id,
                               NULL::text AS state_scheme_id,
                               NULL::text AS centre_scheme_id,
                               NULL::text AS scheme_name,
                               NULL::double precision AS latitude,
                               NULL::double precision AS longitude
                    ) sch ON true
                    """;
        }
        String sql = String.format("""
                SELECT u.id,
                       u.uuid,
                       u.title,
                       u.email,
                       u.phone_number,
                       u.status,
                       ut.c_name AS role,
                       sch.scheme_id,
                       sch.state_scheme_id,
                       sch.centre_scheme_id,
                       sch.scheme_name,
                       sch.latitude AS scheme_latitude,
                       sch.longitude AS scheme_longitude,
                       rs.last_submission_at,
                       rs.first_submission_date,
                       comp.total_days_since_first_submission,
                       rs.submitted_days,
                       comp.reporting_rate_percent,
                       comp.missed_submission_days
                FROM %s.user_table u
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                %s
                LEFT JOIN LATERAL (
                    SELECT
                        MAX(fr.%s) AS last_submission_at,
                        MIN(fr.reading_date) AS first_submission_date,
                        COUNT(DISTINCT fr.reading_date) AS submitted_days
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = u.id
                ) rs ON true
                LEFT JOIN LATERAL (
                    WITH bounds AS (
                        SELECT rs.first_submission_date AS start_date
                        WHERE rs.first_submission_date IS NOT NULL
                    ),
                    days AS (
                        SELECT (bounds.start_date + gs) AS d
                        FROM bounds
                        JOIN generate_series(0, (CURRENT_DATE - bounds.start_date)) gs ON true
                    ),
                    reported AS (
                        SELECT DISTINCT fr.reading_date AS d
                        FROM %s.flow_reading_table fr
                        JOIN bounds ON true
                        WHERE fr.deleted_at IS NULL
                          AND fr.created_by = u.id
                          AND fr.reading_date BETWEEN bounds.start_date AND CURRENT_DATE
                    )
                    SELECT
                        (CURRENT_DATE - bounds.start_date + 1) AS total_days_since_first_submission,
                        ROUND(
                            (rs.submitted_days::numeric * 100.0) / NULLIF((CURRENT_DATE - bounds.start_date + 1), 0),
                            2
                        ) AS reporting_rate_percent,
                        (
                            SELECT array_agg(days.d ORDER BY days.d)
                            FROM days
                            LEFT JOIN reported ON reported.d = days.d
                            WHERE reported.d IS NULL
                        ) AS missed_submission_days
                    FROM bounds
                ) comp ON true
                WHERE u.deleted_at IS NULL
                  AND u.id = ?
                  AND upper(COALESCE(ut.c_name, '')) = 'PUMP_OPERATOR'
                LIMIT 1
                """, schemaName, schemeJoin, timeColumn, schemaName, schemaName);
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Timestamp lastTs = (Timestamp) rs.getObject("last_submission_at");
                LocalDateTime lastSubmissionAt = lastTs == null ? null : lastTs.toLocalDateTime();

                java.sql.Date firstD = (java.sql.Date) rs.getObject("first_submission_date");
                LocalDate firstSubmissionDate = firstD == null ? null : firstD.toLocalDate();

                Number totalDaysN = (Number) rs.getObject("total_days_since_first_submission");
                Integer totalDays = totalDaysN == null ? null : totalDaysN.intValue();
                Number submittedDaysN = (Number) rs.getObject("submitted_days");
                Integer submittedDays = submittedDaysN == null ? null : submittedDaysN.intValue();

                List<LocalDate> missedDays = null;
                Array missedArr = (Array) rs.getObject("missed_submission_days");
                if (missedArr != null) {
                    Object raw = missedArr.getArray();
                    if (raw instanceof java.sql.Date[] sqlDates) {
                        missedDays = new ArrayList<>(sqlDates.length);
                        for (java.sql.Date d : sqlDates) {
                            missedDays.add(d == null ? null : d.toLocalDate());
                        }
                    } else if (raw instanceof Object[] objs) {
                        missedDays = new ArrayList<>(objs.length);
                        for (Object o : objs) {
                            if (o == null) {
                                missedDays.add(null);
                            } else if (o instanceof java.sql.Date d) {
                                missedDays.add(d.toLocalDate());
                            } else if (o instanceof LocalDate d) {
                                missedDays.add(d);
                            } else {
                                missedDays.add(LocalDate.parse(o.toString()));
                            }
                        }
                    }
                }

                return PumpOperatorDetailsDTO.builder()
                        .id(rs.getLong("id"))
                        .uuid(rs.getString("uuid"))
                        .name(pii.safeDecrypt(rs.getString("title")))
                        .email(rs.getString("email"))
                        .phoneNumber(pii.safeDecrypt(rs.getString("phone_number")))
                        .status(mapStatus(getNullableInt(rs, "status")))
                        .schemeId(getNullableInt(rs, "scheme_id"))
                        .stateSchemeId(rs.getString("state_scheme_id"))
                        .centerSchemeId(rs.getString("centre_scheme_id"))
                        .schemeName(rs.getString("scheme_name"))
                        .schemeLatitude(getNullableDouble(rs, "scheme_latitude"))
                        .schemeLongitude(getNullableDouble(rs, "scheme_longitude"))
                        .lastSubmissionAt(lastSubmissionAt)
                        .firstSubmissionDate(firstSubmissionDate)
                        .totalDaysSinceFirstSubmission(totalDays)
                        .submittedDays(submittedDays)
                        .reportingRatePercent((BigDecimal) rs.getObject("reporting_rate_percent"))
                        .missedSubmissionDays(missedDays)
                        .build();
            }, pumpOperatorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<SchemePumpOperatorsDTO> listPumpOperatorsByScheme(
            String schemaName,
            List<Long> schemeIds,
            String schemeName,
            Integer page,
            Integer size
    ) {
        validateSchemaName(schemaName);

        List<Object> baseParams = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                WHERE usm.deleted_at IS NULL
                  AND usm.status = 1
                  AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                """);
        if (schemeIds != null && !schemeIds.isEmpty()) {
            where.append("\n  AND sm.id IN (");
            for (int i = 0; i < schemeIds.size(); i++) {
                if (i > 0) {
                    where.append(", ");
                }
                where.append("?");
                baseParams.add(schemeIds.get(i));
            }
            where.append(")\n");
        }
        if (schemeName != null && !schemeName.trim().isBlank()) {
            where.append("\n  AND sm.scheme_name ILIKE ?\n");
            baseParams.add("%" + schemeName.trim() + "%");
        }

        boolean paginate = page != null && size != null;
        int effectivePage = paginate ? page : 0;
        int effectiveSize = paginate ? size : 0;

        if (!paginate) {
            String sql = String.format("""
                    SELECT t.scheme_id,
                           t.scheme_name,
                           t.user_id,
                           t.uuid,
                           t.name,
                           t.email,
                           t.phone_number,
                           t.status,
                           NULL::bigint AS total_ops
                    FROM (
                        SELECT DISTINCT ON (sm.id, u.id)
                               sm.id AS scheme_id,
                               sm.scheme_name AS scheme_name,
                               u.id AS user_id,
                               u.uuid AS uuid,
                               u.title AS name,
                               u.email AS email,
                               u.phone_number AS phone_number,
                               u.status AS status
                        FROM %s.user_scheme_mapping_table usm
                        JOIN %s.scheme_master_table sm
                          ON sm.id = usm.scheme_id
                         AND sm.deleted_at IS NULL
                        JOIN %s.user_table u
                          ON u.id = usm.user_id
                         AND u.deleted_at IS NULL
                        JOIN common_schema.user_type_master_table ut
                          ON ut.id = u.user_type
                        %s
                        ORDER BY sm.id, u.id, usm.id DESC
                    ) t
                    ORDER BY t.scheme_id ASC, t.user_id ASC
                    """, schemaName, schemaName, schemaName, where);

            record Row(long schemeId,
                       String schemeName,
                       long userId,
                       String uuid,
                       String name,
                       String email,
                       String phoneNumber,
                       Integer status) {
            }

            List<Row> rows = jdbcTemplate.query(sql, (rs, n) -> new Row(
                    rs.getLong("scheme_id"),
                    rs.getString("scheme_name"),
                    rs.getLong("user_id"),
                    rs.getString("uuid"),
                    pii.safeDecrypt(rs.getString("name")),
                    rs.getString("email"),
                    pii.safeDecrypt(rs.getString("phone_number")),
                    getNullableInt(rs, "status")
            ), baseParams.toArray());

            // Group while preserving query order (SQL already orders by scheme_id, user_id).
            Map<Long, SchemePumpOperatorsDTO> grouped = new LinkedHashMap<>();
            for (Row r : rows) {
                SchemePumpOperatorsDTO existing = grouped.get(r.schemeId());
                PumpOperatorSummaryDTO op = PumpOperatorSummaryDTO.builder()
                        .id(r.userId())
                        .uuid(r.uuid())
                        .name(r.name())
                        .email(r.email())
                        .phoneNumber(r.phoneNumber())
                        .status(r.status())
                        .build();

                if (existing == null) {
                    List<PumpOperatorSummaryDTO> ops = new ArrayList<>();
                    ops.add(op);
                    grouped.put(r.schemeId(), SchemePumpOperatorsDTO.builder()
                            .schemeId(r.schemeId())
                            .schemeName(r.schemeName())
                            .pumpOperators(ops)
                            .build());
                } else {
                    // List is mutable because we constructed it above.
                    existing.pumpOperators().add(op);
                }
            }

            return new ArrayList<>(grouped.values());
        }

        // Pagination applies to pump operators within each scheme (page/size are per scheme).
        long offset = (long) effectivePage * (long) effectiveSize;
        long upperExclusive = offset + effectiveSize;

        String metaSql = String.format("""
                WITH latest AS (
                    SELECT DISTINCT ON (sm.id, u.id)
                           sm.id AS scheme_id,
                           sm.scheme_name AS scheme_name,
                           u.id AS user_id
                    FROM %s.user_scheme_mapping_table usm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = usm.scheme_id
                     AND sm.deleted_at IS NULL
                    JOIN %s.user_table u
                      ON u.id = usm.user_id
                     AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    %s
                    ORDER BY sm.id, u.id, usm.id DESC
                )
                SELECT scheme_id,
                       scheme_name,
                       COUNT(*)::bigint AS total_ops
                FROM latest
                GROUP BY scheme_id, scheme_name
                ORDER BY scheme_id ASC
                """, schemaName, schemaName, schemaName, where);

        record SchemeMeta(long schemeId, String schemeName, long totalOps) {
        }
        List<SchemeMeta> metas = jdbcTemplate.query(metaSql, (rs, n) -> new SchemeMeta(
                rs.getLong("scheme_id"),
                rs.getString("scheme_name"),
                rs.getLong("total_ops")
        ), baseParams.toArray());

        Map<Long, SchemePumpOperatorsDTO> grouped = new LinkedHashMap<>();
        for (SchemeMeta m : metas) {
            int totalPages = (int) Math.ceil(m.totalOps() / (double) effectiveSize);
            grouped.put(m.schemeId(), SchemePumpOperatorsDTO.builder()
                    .schemeId(m.schemeId())
                    .schemeName(m.schemeName())
                    .pumpOperators(new ArrayList<>())
                    .page(effectivePage)
                    .size(effectiveSize)
                    .totalPumpOperators(m.totalOps())
                    .totalPages(totalPages)
                    .build());
        }

        String opsSql = String.format("""
                WITH latest AS (
                    SELECT DISTINCT ON (sm.id, u.id)
                           sm.id AS scheme_id,
                           sm.scheme_name AS scheme_name,
                           u.id AS user_id,
                           u.uuid AS uuid,
                           u.title AS name,
                           u.email AS email,
                           u.phone_number AS phone_number,
                           u.status AS status
                    FROM %s.user_scheme_mapping_table usm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = usm.scheme_id
                     AND sm.deleted_at IS NULL
                    JOIN %s.user_table u
                      ON u.id = usm.user_id
                     AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    %s
                    ORDER BY sm.id, u.id, usm.id DESC
                ),
                numbered AS (
                    SELECT l.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY l.scheme_id
                               ORDER BY l.user_id ASC
                           ) AS rn
                    FROM latest l
                )
                SELECT scheme_id,
                       scheme_name,
                       user_id,
                       uuid,
                       name,
                       email,
                       phone_number,
                       status
                FROM numbered
                WHERE rn > ?
                  AND rn <= ?
                ORDER BY scheme_id ASC, rn ASC
                """, schemaName, schemaName, schemaName, where);

        List<Object> opsParams = new ArrayList<>(baseParams);
        opsParams.add(offset);
        opsParams.add(upperExclusive);

        record OpRow(long schemeId,
                     String schemeName,
                     long userId,
                     String uuid,
                     String name,
                     String email,
                     String phoneNumber,
                     Integer status) {
        }
        List<OpRow> ops = jdbcTemplate.query(opsSql, (rs, n) -> new OpRow(
                rs.getLong("scheme_id"),
                rs.getString("scheme_name"),
                rs.getLong("user_id"),
                rs.getString("uuid"),
                pii.safeDecrypt(rs.getString("name")),
                rs.getString("email"),
                pii.safeDecrypt(rs.getString("phone_number")),
                getNullableInt(rs, "status")
        ), opsParams.toArray());

        for (OpRow r : ops) {
            SchemePumpOperatorsDTO dto = grouped.get(r.schemeId());
            if (dto == null) {
                // Fallback: scheme meta query returned nothing, but operator rows exist.
                dto = SchemePumpOperatorsDTO.builder()
                        .schemeId(r.schemeId())
                        .schemeName(r.schemeName())
                        .pumpOperators(new ArrayList<>())
                        .page(effectivePage)
                        .size(effectiveSize)
                        .build();
                grouped.put(r.schemeId(), dto);
            }

            dto.pumpOperators().add(PumpOperatorSummaryDTO.builder()
                    .id(r.userId())
                    .uuid(r.uuid())
                    .name(r.name())
                    .email(r.email())
                    .phoneNumber(r.phoneNumber())
                    .status(r.status())
                    .build());
        }

        return new ArrayList<>(grouped.values());
    }

    public PumpOperatorReadingComplianceDTO getReadingCompliance(String schemaName, long pumpOperatorId) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        // If the operator has no readings, lastSubmissionAt/confirmedReading will be null.
        String sql = String.format("""
                SELECT u.title AS name,
                       fr.last_submission_at,
                       fr.confirmed_reading
                FROM %s.user_table u
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                LEFT JOIN LATERAL (
                    SELECT %s AS last_submission_at,
                           %s AS confirmed_reading
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = u.id
                    ORDER BY fr.%s DESC, fr.id DESC
                    LIMIT 1
                ) fr ON true
                WHERE u.deleted_at IS NULL
                  AND u.id = ?
                  AND upper(COALESCE(ut.c_name, '')) = 'PUMP_OPERATOR'
                LIMIT 1
                """, schemaName, timeColumn, confirmedExpr, schemaName, timeColumn);

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Timestamp ts = (Timestamp) rs.getObject("last_submission_at");
                LocalDateTime lastSubmissionAt = ts == null ? null : ts.toLocalDateTime();
                BigDecimal confirmed = (BigDecimal) rs.getObject("confirmed_reading");
                return PumpOperatorReadingComplianceDTO.builder()
                        .name(pii.safeDecrypt(rs.getString("name")))
                        .lastSubmissionAt(lastSubmissionAt)
                        .confirmedReading(confirmed)
                        .build();
            }, pumpOperatorId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<PumpOperatorReadingComplianceRowDTO> listReadingCompliance(String schemaName, long offset, int limit) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        String sql = String.format("""
                SELECT u.id,
                       u.uuid,
                       u.title AS name,
                       fr.last_submission_at,
                       fr.confirmed_reading
                FROM %s.user_table u
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                LEFT JOIN LATERAL (
                    SELECT %s AS last_submission_at,
                           %s AS confirmed_reading
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = u.id
                    ORDER BY fr.%s DESC, fr.id DESC
                    LIMIT 1
                ) fr ON true
                WHERE u.deleted_at IS NULL
                  AND upper(COALESCE(ut.c_name, '')) = 'PUMP_OPERATOR'
                ORDER BY u.id DESC
                LIMIT ? OFFSET ?
                """, schemaName, timeColumn, confirmedExpr, schemaName, timeColumn);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp ts = (Timestamp) rs.getObject("last_submission_at");
            LocalDateTime lastSubmissionAt = ts == null ? null : ts.toLocalDateTime();
            BigDecimal confirmed = (BigDecimal) rs.getObject("confirmed_reading");
            return PumpOperatorReadingComplianceRowDTO.builder()
                    .id(rs.getLong("id"))
                    .uuid(rs.getString("uuid"))
                    .name(pii.safeDecrypt(rs.getString("name")))
                    .lastSubmissionAt(lastSubmissionAt)
                    .confirmedReading(confirmed)
                    .build();
        }, limit, offset);
    }

    public long countReadingCompliance(String schemaName) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT COUNT(1)
                FROM %s.user_table u
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                WHERE u.deleted_at IS NULL
                  AND upper(COALESCE(ut.c_name, '')) = 'PUMP_OPERATOR'
                """, schemaName);
        Long total = jdbcTemplate.queryForObject(sql, Long.class);
        return total == null ? 0 : total;
    }

    /**
     * The reading set that {@link #listSchemeReadingCompliance} pages over and
     * {@link #countSchemeReadingCompliance} totals. Both build their SQL from this single fragment so
     * the page and its total can never disagree about which readings are in scope — a divergence would
     * silently make the tail of the listing unreachable.
     *
     * <p>Rows are driven from {@code flow_reading_table}, not from the scheme's operator mappings, so a
     * reading counts regardless of the role of whoever submitted it. The scheme mapping is joined in
     * later ({@code submitter}) and only decorates the row, which means a submission stays visible even
     * if the submitter's mapping to the scheme was since removed.
     *
     * <p>Placeholders, in order: {@code schemeId}, {@code submittedByUserId} (twice), {@code startDate},
     * {@code endDate} — see {@link #schemeReadingsArgs}.
     */
    private String schemeReadingsCte(String schemaName, String timeColumn, String confirmedExpr) {
        return String.format("""
                scheme AS (
                    SELECT sm.id AS scheme_id,
                           sm.scheme_name
                    FROM %1$s.scheme_master_table sm
                    WHERE sm.id = ?
                      AND sm.deleted_at IS NULL
                ),
                readings AS (
                    SELECT fr.id AS reading_id,
                           fr.created_by,
                           fr.reading_date,
                           fr.%2$s AS reading_at,
                           %3$s AS confirmed_reading
                    FROM %1$s.flow_reading_table fr
                    JOIN scheme sc
                      ON sc.scheme_id = fr.scheme_id
                    JOIN %1$s.user_table u
                      ON u.id = fr.created_by
                     AND u.deleted_at IS NULL
                    WHERE fr.deleted_at IS NULL
                      AND (CAST(? AS BIGINT) IS NULL OR fr.created_by = ?)
                      AND fr.reading_date >= COALESCE(CAST(? AS DATE), fr.reading_date)
                      AND fr.reading_date <= LEAST(CURRENT_DATE, COALESCE(CAST(? AS DATE), CURRENT_DATE))
                )
                """, schemaName, timeColumn, confirmedExpr);
    }

    /** Positional arguments for the placeholders in {@link #schemeReadingsCte}. */
    private static List<Object> schemeReadingsArgs(
            long schemeId,
            Long submittedByUserId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<Object> args = new ArrayList<>();
        args.add(schemeId);
        args.add(submittedByUserId);
        args.add(submittedByUserId);
        args.add(startDate);
        args.add(endDate);
        return args;
    }

    /**
     * One row per reading submitted against {@code schemeId}, newest first, decorated with the
     * submitter's reading-compliance figures.
     *
     * <p>Every submitter is listed, not just pump operators; {@code submittedByRole} carries the raw
     * {@code user_type_master_table.c_name} so the caller can label the row. The figures that measure a
     * submitter against an expected reporting window — onboarding date, active/missed/inactive days and
     * the reporting rate — come from {@code po_window}, which admits pump operators only, so they are
     * {@code null} for every other role: a section officer has no per-scheme reporting obligation, and
     * deriving a rate from their account-creation date would report an arbitrarily low number.
     * {@code submitted_days} and {@code last_submission_at} are plain counts over the requested range
     * and are therefore populated for all roles.
     */
    public List<SchemeReadingComplianceRowDTO> listSchemeReadingCompliance(
            String schemaName,
            long schemeId,
            Long submittedByUserId,
            LocalDate startDate,
            LocalDate endDate,
            long offset,
            int limit
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return List.of();
        }
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        String sql = "WITH " + schemeReadingsCte(schemaName, timeColumn, confirmedExpr) + String.format("""
                ,
                paged AS (
                    SELECT *
                    FROM readings
                    ORDER BY reading_date DESC, reading_id DESC
                    LIMIT ? OFFSET ?
                ),
                page_submitters AS (
                    SELECT DISTINCT created_by
                    FROM paged
                ),
                submitter AS (
                    SELECT DISTINCT ON (u.id)
                           u.id,
                           u.uuid,
                           u.title AS name,
                           u.email,
                           u.phone_number,
                           u.status,
                           u.created_at::date AS onboarding_date,
                           upper(ut.c_name) AS submitted_by_role,
                           (lower(COALESCE(ut.c_name, '')) = 'pump_operator') AS is_pump_operator,
                           usm.status AS scheme_mapping_status
                    FROM page_submitters ps
                    JOIN %1$s.user_table u
                      ON u.id = ps.created_by
                    LEFT JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    LEFT JOIN %1$s.user_scheme_mapping_table usm
                      ON usm.user_id = u.id
                     AND usm.scheme_id = ?
                     AND usm.deleted_at IS NULL
                    ORDER BY u.id DESC, usm.id DESC
                ),
                po_window AS (
                    SELECT s.id,
                           s.onboarding_date,
                           GREATEST(s.onboarding_date, COALESCE(CAST(? AS DATE), s.onboarding_date))
                               AS effective_start_date,
                           LEAST(CURRENT_DATE, COALESCE(CAST(? AS DATE), CURRENT_DATE))
                               AS effective_end_date
                    FROM submitter s
                    WHERE s.is_pump_operator
                      AND s.onboarding_date IS NOT NULL
                      AND GREATEST(s.onboarding_date, COALESCE(CAST(? AS DATE), s.onboarding_date))
                          <= LEAST(CURRENT_DATE, COALESCE(CAST(? AS DATE), CURRENT_DATE))
                ),
                stats AS (
                    SELECT fr.created_by,
                           COUNT(DISTINCT fr.reading_date) AS submitted_days,
                           MAX(fr.%2$s) AS last_submission_at
                    FROM %1$s.flow_reading_table fr
                    JOIN scheme sc
                      ON sc.scheme_id = fr.scheme_id
                    JOIN page_submitters ps
                      ON ps.created_by = fr.created_by
                    WHERE fr.deleted_at IS NULL
                      AND fr.reading_date >= COALESCE(CAST(? AS DATE), fr.reading_date)
                      AND fr.reading_date <= LEAST(CURRENT_DATE, COALESCE(CAST(? AS DATE), CURRENT_DATE))
                    GROUP BY fr.created_by
                )
                SELECT s.id,
                       s.uuid,
                       s.name,
                       s.submitted_by_role,
                       s.email,
                       s.phone_number,
                       s.status,
                       sc.scheme_id,
                       sc.scheme_name,
                       s.scheme_mapping_status,
                       pw.onboarding_date,
                       CASE
                           WHEN pw.id IS NULL THEN NULL
                           ELSE (pw.effective_end_date - pw.effective_start_date + 1)
                       END AS total_active_days,
                       COALESCE(stats.submitted_days, 0) AS submitted_days,
                       CASE
                           WHEN pw.id IS NULL THEN NULL
                           ELSE GREATEST((pw.effective_end_date - pw.effective_start_date + 1)
                                         - COALESCE(stats.submitted_days, 0), 0)
                       END AS missed_submission_days,
                       CASE
                           WHEN pw.id IS NULL THEN NULL
                           ELSE GREATEST((pw.effective_end_date - pw.effective_start_date + 1)
                                         - COALESCE(stats.submitted_days, 0), 0)
                       END AS inactive_days,
                       CASE
                           WHEN pw.id IS NULL THEN NULL
                           ELSE GREATEST((pw.effective_end_date - pw.effective_start_date + 1)
                                         - COALESCE(stats.submitted_days, 0), 0)
                       END AS missing_submission_count,
                       CASE
                           WHEN pw.id IS NULL THEN NULL
                           WHEN (pw.effective_end_date - pw.effective_start_date + 1) <= 0 THEN NULL
                           ELSE ROUND(
                               (COALESCE(stats.submitted_days, 0)::numeric * 100.0)
                               / (pw.effective_end_date - pw.effective_start_date + 1),
                               2
                           )
                       END AS reporting_rate_percent,
                       paged.reading_date,
                       paged.reading_at,
                       paged.confirmed_reading,
                       stats.last_submission_at
                FROM paged
                JOIN submitter s
                  ON s.id = paged.created_by
                CROSS JOIN scheme sc
                LEFT JOIN po_window pw
                  ON pw.id = s.id
                LEFT JOIN stats
                  ON stats.created_by = s.id
                ORDER BY paged.reading_date DESC, paged.reading_id DESC
                """, schemaName, timeColumn);

        List<Object> args = schemeReadingsArgs(schemeId, submittedByUserId, startDate, endDate);
        args.add(limit);            // paged
        args.add(offset);           // paged
        args.add(schemeId);         // submitter — the mapping row to decorate with
        args.add(startDate);        // po_window — effective_start_date
        args.add(endDate);          // po_window — effective_end_date
        args.add(startDate);        // po_window — the same bounds again, as its non-empty guard
        args.add(endDate);          // po_window
        args.add(startDate);        // stats
        args.add(endDate);          // stats

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp ts = (Timestamp) rs.getObject("reading_at");
            LocalDateTime readingAt = ts == null ? null : ts.toLocalDateTime();
            Timestamp lastTs = (Timestamp) rs.getObject("last_submission_at");
            LocalDateTime lastSubmissionAt = lastTs == null ? null : lastTs.toLocalDateTime();
            String role = rs.getString("submitted_by_role");
            return SchemeReadingComplianceRowDTO.builder()
                    .id(rs.getLong("id"))
                    .uuid(rs.getString("uuid"))
                    .name(pii.safeDecrypt(rs.getString("name")))
                    .submittedByRole(role)
                    .submittedByRoleLabel(UserTypeLabel.shortLabel(role))
                    .email(rs.getString("email"))
                    .phoneNumber(pii.safeDecrypt(rs.getString("phone_number")))
                    .status(mapStatus(getNullableInt(rs, "status")))
                    .schemeId(rs.getLong("scheme_id"))
                    .schemeName(rs.getString("scheme_name"))
                    .schemeMappingStatus(getNullableInt(rs, "scheme_mapping_status"))
                    .onboardingDate(rs.getObject("onboarding_date", LocalDate.class))
                    .totalActiveDays(getNullableInt(rs, "total_active_days"))
                    .submittedDays(getNullableInt(rs, "submitted_days"))
                    .missedSubmissionDays(getNullableInt(rs, "missed_submission_days"))
                    .inactiveDays(getNullableInt(rs, "inactive_days"))
                    .missingSubmissionCount(getNullableInt(rs, "missing_submission_count"))
                    .reportingRatePercent((BigDecimal) rs.getObject("reporting_rate_percent"))
                    .readingDate(rs.getObject("reading_date", LocalDate.class))
                    .readingAt(readingAt)
                    .lastSubmissionAt(lastSubmissionAt)
                    .confirmedReading((BigDecimal) rs.getObject("confirmed_reading"))
                    .build();
        }, args.toArray());
    }

    /**
     * Total for {@link #listSchemeReadingCompliance}, which paginates over readings — one row per
     * reading, not per submitter. The count is therefore over readings rather than distinct submitters:
     * counting submitters made a submitter's every reading after the first unreachable, since the page
     * holding it was rejected as past the end of a shorter total.
     */
    public long countSchemeReadingCompliance(
            String schemaName,
            long schemeId,
            Long submittedByUserId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return 0;
        }
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        String sql = "WITH " + schemeReadingsCte(schemaName, timeColumn, confirmedExpr)
                + "SELECT COUNT(*) FROM readings";
        Long total = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                schemeReadingsArgs(schemeId, submittedByUserId, startDate, endDate).toArray()
        );
        return total == null ? 0 : total;
    }

    private TenantUserStatus mapStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return TenantUserStatus.fromCode(status);
    }
}
