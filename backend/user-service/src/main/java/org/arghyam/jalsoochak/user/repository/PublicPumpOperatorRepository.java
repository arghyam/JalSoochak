package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
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

    public List<PumpOperatorReadingComplianceRowDTO> listReadingCompliance(String schemaName, int offset, int limit) {
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

    public List<PumpOperatorSchemeComplianceRowDTO> listPumpOperatorsBySchemeWithCompliance(
            String schemaName,
            long schemeId,
            long pumpOperatorId,
            LocalDate startDate,
            LocalDate endDate,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return List.of();
        }
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        String sql = String.format("""
                WITH latest_mapping AS (
                    SELECT DISTINCT ON (u.id)
                           u.id,
                           u.uuid,
                           u.title AS name,
                           u.email,
                           u.phone_number,
                           u.status,
                           u.created_at::date AS onboarding_date,
                           usm.status AS scheme_mapping_status,
                           sm.id AS scheme_id,
                           sm.scheme_name
                    FROM %s.user_scheme_mapping_table usm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = usm.scheme_id
                     AND sm.deleted_at IS NULL
                    JOIN %s.user_table u
                      ON u.id = usm.user_id
                     AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    WHERE usm.deleted_at IS NULL
                      AND sm.id = ?
                      AND u.id = ?
                      AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                    ORDER BY u.id DESC, usm.id DESC
                ),
                windowed_mapping AS (
                    SELECT l.*,
                           GREATEST(l.onboarding_date, COALESCE(?, l.onboarding_date)) AS effective_start_date,
                           LEAST(CURRENT_DATE, COALESCE(?, CURRENT_DATE)) AS effective_end_date
                    FROM latest_mapping l
                    WHERE l.onboarding_date IS NOT NULL
                      AND GREATEST(l.onboarding_date, COALESCE(?, l.onboarding_date))
                          <= LEAST(CURRENT_DATE, COALESCE(?, CURRENT_DATE))
                ),
                readings AS (
                    SELECT fr.id AS reading_id,
                           fr.created_by,
                           fr.reading_date,
                           fr.%s AS reading_at,
                           %s AS confirmed_reading
                    FROM %s.flow_reading_table fr
                    JOIN windowed_mapping l
                      ON l.id = fr.created_by
                    WHERE fr.deleted_at IS NULL
                      AND fr.reading_date BETWEEN l.effective_start_date AND l.effective_end_date
                ),
                paged AS (
                    SELECT *
                    FROM readings
                    ORDER BY reading_date DESC, reading_id DESC
                    LIMIT ? OFFSET ?
                ),
                page_ops AS (
                    SELECT DISTINCT created_by
                    FROM paged
                ),
                stats AS (
                    SELECT fr.created_by,
                           COUNT(DISTINCT fr.reading_date) AS submitted_days,
                           MAX(fr.%s) AS last_submission_at
                    FROM %s.flow_reading_table fr
                    JOIN page_ops po
                      ON po.created_by = fr.created_by
                    JOIN windowed_mapping l
                      ON l.id = fr.created_by
                    WHERE fr.deleted_at IS NULL
                      AND fr.reading_date BETWEEN l.effective_start_date AND l.effective_end_date
                    GROUP BY fr.created_by
                )
                SELECT l.id,
                       l.uuid,
                       l.name,
                       l.email,
                       l.phone_number,
                       l.status,
                       l.scheme_id,
                       l.scheme_name,
                       l.scheme_mapping_status,
                       l.onboarding_date,
                       CASE
                           WHEN l.effective_start_date IS NULL OR l.effective_end_date IS NULL THEN NULL
                           ELSE (l.effective_end_date - l.effective_start_date + 1)
                       END AS total_active_days,
                       COALESCE(stats.submitted_days, 0) AS submitted_days,
                       CASE
                           WHEN l.effective_start_date IS NULL OR l.effective_end_date IS NULL THEN NULL
                           ELSE GREATEST((l.effective_end_date - l.effective_start_date + 1) - COALESCE(stats.submitted_days, 0), 0)
                       END AS missed_submission_days,
                       CASE
                           WHEN l.effective_start_date IS NULL OR l.effective_end_date IS NULL THEN NULL
                           ELSE GREATEST((l.effective_end_date - l.effective_start_date + 1) - COALESCE(stats.submitted_days, 0), 0)
                       END AS inactive_days,
                       CASE
                           WHEN l.effective_start_date IS NULL OR l.effective_end_date IS NULL THEN NULL
                           ELSE GREATEST((l.effective_end_date - l.effective_start_date + 1) - COALESCE(stats.submitted_days, 0), 0)
                       END AS missing_submission_count,
                       CASE
                           WHEN l.effective_start_date IS NULL OR l.effective_end_date IS NULL THEN NULL
                           WHEN (l.effective_end_date - l.effective_start_date + 1) <= 0 THEN NULL
                           ELSE ROUND(
                               (COALESCE(stats.submitted_days, 0)::numeric * 100.0)
                               / (l.effective_end_date - l.effective_start_date + 1),
                               2
                           )
                       END AS reporting_rate_percent,
                       paged.reading_date,
                       paged.reading_at,
                       paged.confirmed_reading,
                       stats.last_submission_at
                FROM paged
                JOIN windowed_mapping l
                  ON l.id = paged.created_by
                LEFT JOIN stats
                  ON stats.created_by = l.id
                ORDER BY paged.reading_date DESC, paged.reading_id DESC
                """, schemaName, schemaName, schemaName, timeColumn, confirmedExpr, schemaName, timeColumn, schemaName);

        record RowData(
                Long id,
                String uuid,
                String name,
                String email,
                String phoneNumber,
                Integer status,
                Long schemeId,
                String schemeName,
                Integer schemeMappingStatus,
                LocalDate onboardingDate,
                Integer totalActiveDays,
                Integer submittedDays,
                Integer missedSubmissionDays,
                Integer inactiveDays,
                Integer missingSubmissionCount,
                BigDecimal reportingRatePercent,
                LocalDate readingDate,
                LocalDateTime readingAt,
                LocalDateTime lastSubmissionAt,
                BigDecimal confirmedReading
        ) {
        }

        List<RowData> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp ts = (Timestamp) rs.getObject("reading_at");
            LocalDateTime readingAt = ts == null ? null : ts.toLocalDateTime();
            Timestamp lastTs = (Timestamp) rs.getObject("last_submission_at");
            LocalDateTime lastSubmissionAt = lastTs == null ? null : lastTs.toLocalDateTime();
            BigDecimal confirmed = (BigDecimal) rs.getObject("confirmed_reading");
            return new RowData(
                    rs.getLong("id"),
                    rs.getString("uuid"),
                    pii.safeDecrypt(rs.getString("name")),
                    rs.getString("email"),
                    pii.safeDecrypt(rs.getString("phone_number")),
                    getNullableInt(rs, "status"),
                    rs.getLong("scheme_id"),
                    rs.getString("scheme_name"),
                    getNullableInt(rs, "scheme_mapping_status"),
                    rs.getObject("onboarding_date", LocalDate.class),
                    getNullableInt(rs, "total_active_days"),
                    getNullableInt(rs, "submitted_days"),
                    getNullableInt(rs, "missed_submission_days"),
                    getNullableInt(rs, "inactive_days"),
                    getNullableInt(rs, "missing_submission_count"),
                    (BigDecimal) rs.getObject("reporting_rate_percent"),
                    rs.getObject("reading_date", LocalDate.class),
                    readingAt,
                    lastSubmissionAt,
                    confirmed
            );
        }, schemeId, pumpOperatorId, startDate, endDate, startDate, endDate, limit, offset);

        if (rows.isEmpty()) {
            return List.of();
        }

        List<PumpOperatorSchemeComplianceRowDTO> results = new ArrayList<>(rows.size());
        for (RowData r : rows) {
            results.add(PumpOperatorSchemeComplianceRowDTO.builder()
                    .id(r.id())
                    .uuid(r.uuid())
                    .name(r.name())
                    .email(r.email())
                    .phoneNumber(r.phoneNumber())
                    .status(mapStatus(r.status()))
                    .schemeId(r.schemeId())
                    .schemeName(r.schemeName())
                    .schemeMappingStatus(r.schemeMappingStatus())
                    .onboardingDate(r.onboardingDate())
                    .totalActiveDays(r.totalActiveDays())
                    .submittedDays(r.submittedDays())
                    .missedSubmissionDays(r.missedSubmissionDays())
                    .inactiveDays(r.inactiveDays())
                    .missingSubmissionCount(r.missingSubmissionCount())
                    .reportingRatePercent(r.reportingRatePercent())
                    .readingDate(r.readingDate())
                    .readingAt(r.readingAt())
                    .lastSubmissionAt(r.lastSubmissionAt())
                    .confirmedReading(r.confirmedReading())
                    .build());
        }

        return results;
    }

    public long countPumpOperatorsBySchemeWithCompliance(
            String schemaName,
            long schemeId,
            long pumpOperatorId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return 0;
        }
        String sql = String.format("""
                WITH latest_mapping AS (
                    SELECT DISTINCT ON (u.id)
                           u.id,
                           u.created_at::date AS onboarding_date
                    FROM %s.user_scheme_mapping_table usm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = usm.scheme_id
                     AND sm.deleted_at IS NULL
                    JOIN %s.user_table u
                      ON u.id = usm.user_id
                     AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    WHERE usm.deleted_at IS NULL
                      AND sm.id = ?
                      AND u.id = ?
                      AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                    ORDER BY u.id DESC, usm.id DESC
                ),
                windowed_mapping AS (
                    SELECT l.*,
                           GREATEST(l.onboarding_date, COALESCE(?, l.onboarding_date)) AS effective_start_date,
                           LEAST(CURRENT_DATE, COALESCE(?, CURRENT_DATE)) AS effective_end_date
                    FROM latest_mapping l
                    WHERE l.onboarding_date IS NOT NULL
                      AND GREATEST(l.onboarding_date, COALESCE(?, l.onboarding_date))
                          <= LEAST(CURRENT_DATE, COALESCE(?, CURRENT_DATE))
                )
                SELECT COUNT(DISTINCT l.id)
                FROM windowed_mapping l
                JOIN %s.flow_reading_table fr
                  ON fr.created_by = l.id
                WHERE fr.deleted_at IS NULL
                  AND fr.reading_date BETWEEN l.effective_start_date AND l.effective_end_date
                """, schemaName, schemaName, schemaName, schemaName);
        Long total = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                schemeId,
                pumpOperatorId,
                startDate,
                endDate,
                startDate,
                endDate
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
