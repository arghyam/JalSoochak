package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeSummaryDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * All SQL in this repository uses {@code String.format} to inject only pre-validated,
 * internal-only values:
 * <ul>
 *   <li>{@code schemaName} — validated by {@link #validateSchemaName} against
 *       {@code ^[a-z_][a-z0-9_]*$}.</li>
 *   <li>Column name fragments ({@code timeColumn}, {@code confirmedExpr}) — returned from
 *       internal helpers that produce only hardcoded SQL literals.</li>
 *   <li>WHERE/ORDER-BY fragments — assembled from hardcoded string constants with
 *       {@code ?} placeholders; user input is always bound as a parameter.</li>
 *   <li>IN-clause placeholders — built as {@code "?, ?, ..."} strings from collection size.</li>
 * </ul>
 * No user-supplied data is ever concatenated into the query string.
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
public class PersonSchemeRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService pii;

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
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

    private String schemeOrderBy(String sortBy, String sortDir) {
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String key = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        String col = switch (key) {
            case "stateschemeid", "state_scheme_id" -> "ps.state_scheme_id";
            case "schemeid", "id" -> "ps.id";
            case "schemename", "scheme_name" -> "ps.scheme_name";
            default -> "ps.scheme_name";
        };
        return "ORDER BY " + col + " " + dir;
    }

    public long countSchemesByPerson(String schemaName, long personId, String schemeName) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return 0;
        }
        StringBuilder where = new StringBuilder("""
                WHERE usm.deleted_at IS NULL
                  AND usm.status = 1
                  AND usm.user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(personId);
        if (schemeName != null && !schemeName.isBlank()) {
            where.append(" AND sm.scheme_name ILIKE ?\n");
            args.add("%" + schemeName.trim() + "%");
        }
        String sql = String.format("""
                SELECT COUNT(DISTINCT sm.id)
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.scheme_master_table sm
                  ON sm.id = usm.scheme_id
                 AND sm.deleted_at IS NULL
                %s
                """, schemaName, schemaName, where);
        Long total = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<PersonSchemeDetailsDTO> listSchemesByPerson(
            String schemaName,
            long personId,
            String schemeName,
            String sortBy,
            String sortDir,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return List.of();
        }

        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        StringBuilder where = new StringBuilder("""
                WHERE usm.deleted_at IS NULL
                  AND usm.status = 1
                  AND usm.user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(personId);
        if (schemeName != null && !schemeName.isBlank()) {
            where.append(" AND sm.scheme_name ILIKE ?\n");
            args.add("%" + schemeName.trim() + "%");
        }

        String sql = String.format("""
                WITH person_schemes AS (
                    SELECT DISTINCT sm.id, sm.scheme_name, sm.state_scheme_id
                    FROM %s.user_scheme_mapping_table usm
                    JOIN %s.scheme_master_table sm
                      ON sm.id = usm.scheme_id
                     AND sm.deleted_at IS NULL
                    %s
                )
                SELECT ps.id,
                       ps.scheme_name,
                       ps.state_scheme_id,
                       lr.last_reading,
                       lr.last_reading_at,
                       lr.last_water_supplied,
                       yr.yesterday_reading
                FROM person_schemes ps
                LEFT JOIN LATERAL (
                    WITH ordered AS (
                        SELECT fr.%s AS reading_at,
                               %s AS confirmed_reading,
                               LAG(%s) OVER (ORDER BY fr.%s DESC, fr.id DESC) AS prev_confirmed
                        FROM %s.flow_reading_table fr
                        WHERE fr.deleted_at IS NULL
                          AND fr.scheme_id = ps.id
                    )
                    SELECT confirmed_reading AS last_reading,
                           reading_at AS last_reading_at,
                           CASE
                               WHEN prev_confirmed IS NULL THEN NULL
                               ELSE confirmed_reading - prev_confirmed
                           END AS last_water_supplied
                    FROM ordered
                    ORDER BY reading_at DESC
                    LIMIT 1
                ) lr ON true
                LEFT JOIN LATERAL (
                    SELECT %s AS yesterday_reading
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.scheme_id = ps.id
                      AND fr.reading_date = CURRENT_DATE - INTERVAL '1 day'
                    ORDER BY fr.%s DESC, fr.id DESC
                    LIMIT 1
                ) yr ON true
                %s
                LIMIT ? OFFSET ?
                """, schemaName, schemaName, where, timeColumn, confirmedExpr, confirmedExpr, timeColumn, schemaName,
                confirmedExpr, schemaName, timeColumn, schemeOrderBy(sortBy, sortDir));

        List<Object> finalArgs = new ArrayList<>(args);
        finalArgs.add(limit);
        finalArgs.add(offset);

        record Row(
                long schemeId,
                String schemeNameValue,
                String stateSchemeId,
                BigDecimal lastReading,
                LocalDateTime lastReadingAt,
                BigDecimal lastWaterSupplied,
                BigDecimal yesterdayReading
        ) {
        }

        List<Row> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp lastTs = (Timestamp) rs.getObject("last_reading_at");
            LocalDateTime lastReadingAt = lastTs == null ? null : lastTs.toLocalDateTime();
            return new Row(
                    rs.getLong("id"),
                    rs.getString("scheme_name"),
                    rs.getString("state_scheme_id"),
                    (BigDecimal) rs.getObject("last_reading"),
                    lastReadingAt,
                    (BigDecimal) rs.getObject("last_water_supplied"),
                    (BigDecimal) rs.getObject("yesterday_reading")
            );
        }, finalArgs.toArray());

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> schemeIds = rows.stream().map(Row::schemeId).toList();
        Map<Long, List<String>> pumpOperators = fetchPumpOperatorsBySchemes(schemaName, schemeIds);

        List<PersonSchemeDetailsDTO> results = new ArrayList<>(rows.size());
        for (Row row : rows) {
            results.add(PersonSchemeDetailsDTO.builder()
                    .schemeId(row.schemeId())
                    .schemeName(row.schemeNameValue())
                    .stateSchemeId(row.stateSchemeId())
                    .pumpOperatorNames(pumpOperators.getOrDefault(row.schemeId(), List.of()))
                    .lastReading(row.lastReading())
                    .lastReadingAt(row.lastReadingAt())
                    .yesterdayReading(row.yesterdayReading())
                    .lastWaterSupplied(row.lastWaterSupplied())
                    .build());
        }
        return results;
    }

    private Map<Long, List<String>> fetchPumpOperatorsBySchemes(String schemaName, List<Long> schemeIds) {
        if (schemeIds == null || schemeIds.isEmpty()) {
            return Map.of();
        }
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return Map.of();
        }
        String placeholders = String.join(", ", schemeIds.stream().map(v -> "?").toList());
        String sql = String.format("""
                SELECT usm.scheme_id,
                       u.title AS name
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u
                  ON u.id = usm.user_id
                 AND u.deleted_at IS NULL
                JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                WHERE usm.deleted_at IS NULL
                  AND usm.status = 1
                  AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                  AND usm.scheme_id IN (%s)
                ORDER BY usm.scheme_id ASC, u.id ASC
                """, schemaName, schemaName, placeholders);

        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            long schemeId = rs.getLong("scheme_id");
            String decrypted = pii.safeDecrypt(rs.getString("name"));
            grouped.computeIfAbsent(schemeId, k -> new ArrayList<>()).add(decrypted);
        }, schemeIds.toArray());
        return grouped;
    }

    public SchemeDetailsWithReportingDTO getSchemeDetails(String schemaName, long schemeId) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);

        String sql = String.format("""
                SELECT sm.id,
                       sm.scheme_name,
                       sm.state_scheme_id,
                       rs.last_submission_at,
                       comp.reporting_rate_percent
                FROM %s.scheme_master_table sm
                LEFT JOIN LATERAL (
                    SELECT MAX(fr.%s) AS last_submission_at,
                           MIN(fr.reading_date) AS first_submission_date,
                           COUNT(DISTINCT fr.reading_date) AS submitted_days
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.scheme_id = sm.id
                ) rs ON true
                LEFT JOIN LATERAL (
                    WITH bounds AS (
                        SELECT rs.first_submission_date AS start_date
                        WHERE rs.first_submission_date IS NOT NULL
                    )
                    SELECT ROUND(
                        (rs.submitted_days::numeric * 100.0)
                        / NULLIF((CURRENT_DATE - bounds.start_date + 1), 0),
                        2
                    ) AS reporting_rate_percent
                    FROM bounds
                ) comp ON true
                WHERE sm.deleted_at IS NULL
                  AND sm.id = ?
                LIMIT 1
                """, schemaName, timeColumn, schemaName);

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Timestamp lastTs = (Timestamp) rs.getObject("last_submission_at");
                LocalDateTime lastSubmissionAt = lastTs == null ? null : lastTs.toLocalDateTime();
                return SchemeDetailsWithReportingDTO.builder()
                        .schemeId(rs.getLong("id"))
                        .schemeName(rs.getString("scheme_name"))
                        .stateSchemeId(rs.getString("state_scheme_id"))
                        .lastSubmissionAt(lastSubmissionAt)
                        .reportingRatePercent((BigDecimal) rs.getObject("reporting_rate_percent"))
                        .build();
            }, schemeId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public long countSchemeReadings(String schemaName, long schemeId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT COUNT(1)
                FROM %s.flow_reading_table fr
                JOIN %s.user_table u
                  ON u.id = fr.created_by
                 AND u.deleted_at IS NULL
                JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                WHERE fr.deleted_at IS NULL
                  AND fr.scheme_id = ?
                  AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                """, schemaName, schemaName);
        Long total = jdbcTemplate.queryForObject(sql, Long.class, schemeId);
        return total == null ? 0 : total;
    }

    public List<SchemeReadingSubmissionDTO> listSchemeReadings(
            String schemaName,
            long schemeId,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        String sql = String.format("""
                WITH ordered AS (
                    SELECT fr.id,
                           fr.created_by,
                           fr.%s AS reading_at,
                           %s AS confirmed_reading,
                           LAG(%s) OVER (ORDER BY fr.%s ASC, fr.id ASC) AS prev_confirmed
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.scheme_id = ?
                )
                SELECT o.created_by,
                       o.reading_at,
                       o.confirmed_reading,
                       CASE
                           WHEN o.prev_confirmed IS NULL THEN NULL
                           ELSE o.confirmed_reading - o.prev_confirmed
                       END AS water_supplied,
                       u.title AS name
                FROM ordered o
                LEFT JOIN %s.user_table u
                  ON u.id = o.created_by
                 AND u.deleted_at IS NULL
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                WHERE lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                ORDER BY o.reading_at DESC, o.id DESC
                LIMIT ? OFFSET ?
                """, timeColumn, confirmedExpr, confirmedExpr, timeColumn, schemaName, schemaName);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp ts = (Timestamp) rs.getObject("reading_at");
            LocalDateTime readingAt = ts == null ? null : ts.toLocalDateTime();
            return SchemeReadingSubmissionDTO.builder()
                    .pumpOperatorId(rs.getLong("created_by"))
                    .pumpOperatorName(pii.safeDecrypt(rs.getString("name")))
                    .submittedAt(readingAt)
                    .readingValue((BigDecimal) rs.getObject("confirmed_reading"))
                    .waterSupplied((BigDecimal) rs.getObject("water_supplied"))
                    .build();
        }, schemeId, limit, offset);
    }

    private String pumpOperatorOrderBy(String sortBy, String sortDir) {
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String key = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        String col = switch (key) {
            case "name", "title" -> "o.id";
            case "status" -> "o.status";
            case "lastsubmissionat" -> "last.last_submission_at";
            default -> "o.id";
        };
        return "ORDER BY " + col + " " + dir;
    }

    public long countPumpOperatorsByPerson(
            String schemaName,
            long personId,
            String name,
            Integer status,
            Integer durationDays
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return 0;
        }
        String nameFilter = buildNameFilter(schemaName, name);
        List<Object> args = new ArrayList<>();
        args.add(personId);
        if (nameFilter != null) {
            args.add(pii.hmac(name.trim().toLowerCase(Locale.ROOT)));
        }
        if (status != null) {
            args.add(status);
        }
        if (durationDays != null) {
            args.add(durationDays);
        }

        String sql = String.format("""
                WITH person_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM %s.user_scheme_mapping_table usm
                    WHERE usm.deleted_at IS NULL
                      AND usm.status = 1
                      AND usm.user_id = ?
                ),
                ops AS (
                    SELECT DISTINCT u.id,
                                    u.status,
                                    u.title_hash
                    FROM %s.user_scheme_mapping_table usm
                    JOIN person_schemes ps
                      ON ps.scheme_id = usm.scheme_id
                    JOIN %s.user_table u
                      ON u.id = usm.user_id
                     AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    WHERE usm.deleted_at IS NULL
                      AND usm.status = 1
                      AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                      %s
                      %s
                )
                SELECT COUNT(1)
                FROM ops o
                LEFT JOIN LATERAL (
                    SELECT MAX(fr.%s) AS last_submission_at
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = o.id
                ) last ON true
                %s
                """, schemaName, schemaName, schemaName,
                nameFilter == null ? "" : "AND u.title_hash = ?",
                status == null ? "" : "AND u.status = ?",
                resolveFlowReadingTimeColumn(schemaName), schemaName,
                durationDays == null ? "" : "WHERE last.last_submission_at::date >= CURRENT_DATE - ?");

        Long total = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<PumpOperatorSummaryWithMetricsDTO> listPumpOperatorsByPerson(
            String schemaName,
            long personId,
            String name,
            Integer status,
            Integer durationDays,
            String sortBy,
            String sortDir,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return List.of();
        }
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        String nameFilter = buildNameFilter(schemaName, name);
        List<Object> args = new ArrayList<>();
        args.add(personId);
        if (nameFilter != null) {
            args.add(pii.hmac(name.trim().toLowerCase(Locale.ROOT)));
        }
        if (status != null) {
            args.add(status);
        }
        if (durationDays != null) {
            args.add(durationDays);
        }
        args.add(limit);
        args.add(offset);

        String sql = String.format("""
                WITH person_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM %s.user_scheme_mapping_table usm
                    WHERE usm.deleted_at IS NULL
                      AND usm.status = 1
                      AND usm.user_id = ?
                ),
                ops AS (
                    SELECT DISTINCT u.id,
                                    u.uuid,
                                    u.title,
                                    u.status,
                                    u.title_hash
                    FROM %s.user_scheme_mapping_table usm
                    JOIN person_schemes ps
                      ON ps.scheme_id = usm.scheme_id
                    JOIN %s.user_table u
                      ON u.id = usm.user_id
                     AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type
                    WHERE usm.deleted_at IS NULL
                      AND usm.status = 1
                      AND lower(COALESCE(ut.c_name, '')) = 'pump_operator'
                      %s
                      %s
                )
                SELECT o.id,
                       o.uuid,
                       o.title,
                       o.status,
                       last.last_submission_at,
                       last.last_water_supplied,
                       comp.reporting_rate_percent
                FROM ops o
                LEFT JOIN LATERAL (
                    WITH ordered AS (
                        SELECT fr.%s AS reading_at,
                               %s AS confirmed_reading,
                               LAG(%s) OVER (ORDER BY fr.%s DESC, fr.id DESC) AS prev_confirmed
                        FROM %s.flow_reading_table fr
                        WHERE fr.deleted_at IS NULL
                          AND fr.created_by = o.id
                    )
                    SELECT reading_at AS last_submission_at,
                           CASE
                               WHEN prev_confirmed IS NULL THEN NULL
                               ELSE confirmed_reading - prev_confirmed
                           END AS last_water_supplied
                    FROM ordered
                    ORDER BY reading_at DESC
                    LIMIT 1
                ) last ON true
                LEFT JOIN LATERAL (
                    SELECT MIN(fr.reading_date) AS first_submission_date,
                           COUNT(DISTINCT fr.reading_date) AS submitted_days
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = o.id
                ) rs ON true
                LEFT JOIN LATERAL (
                    WITH bounds AS (
                        SELECT rs.first_submission_date AS start_date
                        WHERE rs.first_submission_date IS NOT NULL
                    )
                    SELECT ROUND(
                        (rs.submitted_days::numeric * 100.0)
                        / NULLIF((CURRENT_DATE - bounds.start_date + 1), 0),
                        2
                    ) AS reporting_rate_percent
                    FROM bounds
                ) comp ON true
                %s
                %s
                LIMIT ? OFFSET ?
                """, schemaName, schemaName, schemaName,
                nameFilter == null ? "" : "AND u.title_hash = ?",
                status == null ? "" : "AND u.status = ?",
                timeColumn, confirmedExpr, confirmedExpr, timeColumn, schemaName,
                schemaName,
                durationDays == null ? "" : "WHERE last.last_submission_at::date >= CURRENT_DATE - ?",
                pumpOperatorOrderBy(sortBy, sortDir));

        record Row(
                long id,
                String uuid,
                String nameValue,
                Integer statusValue,
                LocalDateTime lastSubmissionAt,
                BigDecimal lastWaterSupplied,
                BigDecimal reportingRatePercent
        ) {
        }

        List<Row> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp lastTs = (Timestamp) rs.getObject("last_submission_at");
            LocalDateTime lastSubmissionAt = lastTs == null ? null : lastTs.toLocalDateTime();
            return new Row(
                    rs.getLong("id"),
                    rs.getString("uuid"),
                    pii.safeDecrypt(rs.getString("title")),
                    rs.getObject("status") == null ? null : ((Number) rs.getObject("status")).intValue(),
                    lastSubmissionAt,
                    (BigDecimal) rs.getObject("last_water_supplied"),
                    (BigDecimal) rs.getObject("reporting_rate_percent")
            );
        }, args.toArray());

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = rows.stream().map(Row::id).toList();
        Map<Long, List<PumpOperatorSchemeSummaryDTO>> schemes = fetchSchemesForPumpOperators(schemaName, personId, userIds);

        List<PumpOperatorSummaryWithMetricsDTO> results = new ArrayList<>(rows.size());
        for (Row row : rows) {
            results.add(PumpOperatorSummaryWithMetricsDTO.builder()
                    .id(row.id())
                    .uuid(row.uuid())
                    .name(row.nameValue())
                    .status(mapStatus(row.statusValue()))
                    .schemes(schemes.getOrDefault(row.id(), List.of()))
                    .reportingRatePercent(row.reportingRatePercent())
                    .lastSubmissionAt(row.lastSubmissionAt())
                    .lastWaterSupplied(row.lastWaterSupplied())
                    .build());
        }
        return results;
    }

    private String buildNameFilter(String schemaName, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (!columnExists(schemaName, "user_table", "title_hash")) {
            throw new IllegalArgumentException("Name filtering is not supported for this tenant schema");
        }
        return "title_hash";
    }

    private Map<Long, List<PumpOperatorSchemeSummaryDTO>> fetchSchemesForPumpOperators(
            String schemaName,
            long personId,
            List<Long> userIds
    ) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        if (!tableExists(schemaName, "user_scheme_mapping_table")) {
            return Map.of();
        }

        String userPlaceholders = String.join(", ", userIds.stream().map(v -> "?").toList());
        String sql = String.format("""
                WITH person_schemes AS (
                    SELECT DISTINCT usm.scheme_id
                    FROM %s.user_scheme_mapping_table usm
                    WHERE usm.deleted_at IS NULL
                      AND usm.status = 1
                      AND usm.user_id = ?
                )
                SELECT usm.user_id,
                       sm.id AS scheme_id,
                       sm.scheme_name,
                       sm.state_scheme_id
                FROM %s.user_scheme_mapping_table usm
                JOIN person_schemes ps
                  ON ps.scheme_id = usm.scheme_id
                JOIN %s.scheme_master_table sm
                  ON sm.id = usm.scheme_id
                 AND sm.deleted_at IS NULL
                WHERE usm.deleted_at IS NULL
                  AND usm.status = 1
                  AND usm.user_id IN (%s)
                ORDER BY usm.user_id ASC, sm.id ASC
                """, schemaName, schemaName, schemaName, userPlaceholders);

        List<Object> args = new ArrayList<>();
        args.add(personId);
        args.addAll(userIds);

        Map<Long, List<PumpOperatorSchemeSummaryDTO>> grouped = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            long userId = rs.getLong("user_id");
            PumpOperatorSchemeSummaryDTO scheme = PumpOperatorSchemeSummaryDTO.builder()
                    .schemeId(rs.getLong("scheme_id"))
                    .schemeName(rs.getString("scheme_name"))
                    .stateSchemeId(rs.getString("state_scheme_id"))
                    .build();
            grouped.computeIfAbsent(userId, k -> new ArrayList<>()).add(scheme);
        }, args.toArray());

        return grouped;
    }

    public long countPumpOperatorReadings(
            String schemaName,
            long pumpOperatorId,
            String schemeName
    ) {
        validateSchemaName(schemaName);
        List<Object> args = new ArrayList<>();
        args.add(pumpOperatorId);
        String filter = "";
        if (schemeName != null && !schemeName.isBlank()) {
            filter = " AND sm.scheme_name ILIKE ? ";
            args.add("%" + schemeName.trim() + "%");
        }
        String sql = String.format("""
                SELECT COUNT(1)
                FROM %s.flow_reading_table fr
                JOIN %s.scheme_master_table sm
                  ON sm.id = fr.scheme_id
                 AND sm.deleted_at IS NULL
                WHERE fr.deleted_at IS NULL
                  AND fr.created_by = ?
                  %s
                """, schemaName, schemaName, filter);
        Long total = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String readingOrderBy(String sortBy, String sortDir) {
        String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        String key = sortBy == null ? "" : sortBy.trim().toLowerCase(Locale.ROOT);
        String col = switch (key) {
            case "schemename", "scheme_name" -> "sm.scheme_name";
            case "readingat", "submittedat" -> "o.reading_at";
            default -> "o.reading_at";
        };
        return "ORDER BY " + col + " " + dir + ", o.id DESC";
    }

    public List<PumpOperatorReadingDetailDTO> listPumpOperatorReadings(
            String schemaName,
            long pumpOperatorId,
            String schemeName,
            String sortBy,
            String sortDir,
            int offset,
            int limit
    ) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String confirmedExpr = resolveConfirmedReadingExpression(schemaName, "fr");

        List<Object> args = new ArrayList<>();
        args.add(pumpOperatorId);

        String filter = "";
        if (schemeName != null && !schemeName.isBlank()) {
            filter = " AND sm.scheme_name ILIKE ? ";
            args.add("%" + schemeName.trim() + "%");
        }
        args.add(limit);
        args.add(offset);

        String sql = String.format("""
                WITH ordered AS (
                    SELECT fr.id,
                           fr.scheme_id,
                           fr.%s AS reading_at,
                           %s AS confirmed_reading,
                           LAG(%s) OVER (
                               PARTITION BY fr.scheme_id
                               ORDER BY fr.%s ASC, fr.id ASC
                           ) AS prev_confirmed
                    FROM %s.flow_reading_table fr
                    WHERE fr.deleted_at IS NULL
                      AND fr.created_by = ?
                )
                SELECT o.scheme_id,
                       o.reading_at,
                       o.confirmed_reading,
                       CASE
                           WHEN o.prev_confirmed IS NULL THEN NULL
                           ELSE o.confirmed_reading - o.prev_confirmed
                       END AS water_supplied,
                       sm.scheme_name,
                       sm.state_scheme_id
                FROM ordered o
                JOIN %s.scheme_master_table sm
                  ON sm.id = o.scheme_id
                 AND sm.deleted_at IS NULL
                WHERE 1=1
                  %s
                %s
                LIMIT ? OFFSET ?
                """, timeColumn, confirmedExpr, confirmedExpr, timeColumn, schemaName, schemaName,
                filter, readingOrderBy(sortBy, sortDir));

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Timestamp ts = (Timestamp) rs.getObject("reading_at");
            LocalDateTime readingAt = ts == null ? null : ts.toLocalDateTime();
            return PumpOperatorReadingDetailDTO.builder()
                    .schemeId(rs.getLong("scheme_id"))
                    .schemeName(rs.getString("scheme_name"))
                    .stateSchemeId(rs.getString("state_scheme_id"))
                    .readingAt(readingAt)
                    .readingValue((BigDecimal) rs.getObject("confirmed_reading"))
                    .waterSupplied((BigDecimal) rs.getObject("water_supplied"))
                    .build();
        }, args.toArray());
    }

    private Integer normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String s = status.trim().toLowerCase(Locale.ROOT);
        if (Objects.equals(s, "active")) {
            return 1;
        }
        if (Objects.equals(s, "inactive")) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid status value: " + status);
        }
    }

    public Integer parseStatus(String status) {
        return normalizeStatus(status);
    }

    private TenantUserStatus mapStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return TenantUserStatus.fromCode(status);
    }
}
