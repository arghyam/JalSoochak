package org.arghyam.jalsoochak.telemetry.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@RequiredArgsConstructor
public class TelemetryTenantRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService piiEncryptionService;
    private static final String SCHEME_SELECTION_CORRELATION_PREFIX = "scheme-selection-";
    private static final int OPERATOR_LOOKUP_CACHE_SIZE = 10_000;
    private final Map<String, String> phoneToSchemaCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > OPERATOR_LOOKUP_CACHE_SIZE;
                }
            }
    );
    @Value("${telemetry.cache.metadata.enabled:true}")
    private boolean metadataCacheEnabled = true;
    @Value("${telemetry.cache.tenant-schemas-ttl-ms:300000}")
    private long tenantSchemaListCacheTtlMs = 300_000L;
    @Value("${telemetry.cache.column-exists-ttl-ms:600000}")
    private long columnExistsCacheTtlMs = 600_000L;
    private final Object metadataCacheLock = new Object();
    private final Map<String, TimedCacheValue<Boolean>> columnExistsCache = new ConcurrentHashMap<>();
    private volatile List<String> tenantSchemasCache = List.of();
    private volatile long tenantSchemasCacheExpiresAtMs = 0L;

    public boolean existsSchemeById(String schemaName, Long schemeId) {
        validateSchemaName(schemaName);
        String sql = String.format("SELECT EXISTS (SELECT 1 FROM %s.scheme_master_table WHERE id = ?)", schemaName);
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, schemeId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<TelemetryOperator> findOperatorById(String schemaName, Long operatorId) {
        validateSchemaName(schemaName);
        String languageColumn = resolveSelectColumn(schemaName, "user_table", "language_id", "NULL::integer AS language_id");
        String sql = String.format("""
                SELECT id, tenant_id, title, email, phone_number, language_id
                FROM %s.user_table
                WHERE id = ?
                LIMIT 1
                """, schemaName);
        sql = sql.replace("language_id", languageColumn);
        List<TelemetryOperator> rows = jdbcTemplate.query(sql, (rs, n) -> mapOperator(rs), operatorId);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryOperatorWithSchema> findOperatorByPhoneAcrossTenants(String phoneNumber) {
        return findOperatorByPhoneAcrossTenants(phoneNumber, null);
    }

    public Optional<TelemetryOperatorWithSchema> findOperatorByPhoneAcrossTenants(String phoneNumber, Integer preferredTenantId) {
        String normalizedPhone = normalizePhone(phoneNumber);
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return Optional.empty();
        }

        if (preferredTenantId != null) {
            Optional<String> preferredSchema = findSchemaByTenantId(preferredTenantId);
            if (preferredSchema.isPresent()) {
                Optional<TelemetryOperator> preferredMatch = findOperatorByPhone(
                        preferredSchema.get(),
                        phoneNumber,
                        normalizedPhone
                );
                if (preferredMatch.isPresent()) {
                    phoneToSchemaCache.put(normalizedPhone, preferredSchema.get());
                    return Optional.of(new TelemetryOperatorWithSchema(preferredSchema.get(), preferredMatch.get()));
                }
            }
        }

        String cachedSchema = phoneToSchemaCache.get(normalizedPhone);
        if (cachedSchema != null) {
            Optional<TelemetryOperator> cachedMatch = findOperatorByPhone(cachedSchema, phoneNumber, normalizedPhone);
            if (cachedMatch.isPresent()) {
                return Optional.of(new TelemetryOperatorWithSchema(cachedSchema, cachedMatch.get()));
            }
            phoneToSchemaCache.remove(normalizedPhone);
        }

        List<String> schemas = getTenantSchemasCached();
        TelemetryOperatorWithSchema firstMatch = null;
        for (String schemaName : schemas) {
            Optional<TelemetryOperator> operator = findOperatorByPhone(schemaName, phoneNumber, normalizedPhone);
            if (operator.isEmpty()) {
                continue;
            }

            TelemetryOperatorWithSchema match = new TelemetryOperatorWithSchema(schemaName, operator.get());
            if (preferredTenantId != null && preferredTenantId.equals(match.operator().tenantId())) {
                phoneToSchemaCache.put(normalizedPhone, schemaName);
                return Optional.of(match);
            }

            if (preferredTenantId == null) {
                phoneToSchemaCache.put(normalizedPhone, schemaName);
                return Optional.of(match);
            }

            if (firstMatch == null) {
                firstMatch = match;
            }
        }
        if (firstMatch != null) {
            phoneToSchemaCache.put(normalizedPhone, firstMatch.schemaName());
        }
        return Optional.ofNullable(firstMatch);
    }

    public Optional<TelemetryOperatorWithSchema> findOperatorByPhoneHashAcrossTenants(String phoneHash, Integer preferredTenantId) {
        if (phoneHash == null || phoneHash.isBlank()) {
            return Optional.empty();
        }
        String normalizedHash = phoneHash.trim().toLowerCase();
        if (!normalizedHash.matches("^[a-f0-9]{64}$")) {
            return Optional.empty();
        }

        if (preferredTenantId != null) {
            Optional<String> preferredSchema = findSchemaByTenantId(preferredTenantId);
            if (preferredSchema.isPresent()) {
                Optional<TelemetryOperator> preferredMatch = findOperatorByPhoneHashValue(preferredSchema.get(), normalizedHash);
                if (preferredMatch.isPresent()) {
                    return Optional.of(new TelemetryOperatorWithSchema(preferredSchema.get(), preferredMatch.get()));
                }
            }
        }

        List<String> schemas = getTenantSchemasCached();
        TelemetryOperatorWithSchema firstMatch = null;
        for (String schemaName : schemas) {
            Optional<TelemetryOperator> operator = findOperatorByPhoneHashValue(schemaName, normalizedHash);
            if (operator.isEmpty()) {
                continue;
            }

            TelemetryOperatorWithSchema match = new TelemetryOperatorWithSchema(schemaName, operator.get());
            if (preferredTenantId != null && preferredTenantId.equals(match.operator().tenantId())) {
                return Optional.of(match);
            }
            if (firstMatch == null) {
                firstMatch = match;
            }
        }
        return Optional.ofNullable(firstMatch);
    }

    public Optional<Long> findFirstSchemeForUser(String schemaName, Long userId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT usm.scheme_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.scheme_master_table sm ON sm.id = usm.scheme_id
                WHERE usm.user_id = ?
                  AND usm.status = 1
                  AND usm.deleted_at IS NULL
                  AND sm.deleted_at IS NULL
                ORDER BY usm.id
                LIMIT 1
                """, schemaName, schemaName);
        List<Long> rows = jdbcTemplate.query(sql, (rs, n) -> toLong(rs.getObject("scheme_id")), userId);
        return rows.stream().findFirst();
    }

    public List<TelemetrySchemeOption> findSchemesForUser(String schemaName, Long userId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT usm.scheme_id AS id,
                       sm.scheme_name AS name,
                       MIN(usm.id) AS mapping_order
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.scheme_master_table sm ON sm.id = usm.scheme_id
                WHERE usm.user_id = ?
                  AND usm.status = 1
                  AND usm.deleted_at IS NULL
                  AND sm.status = 1
                  AND sm.deleted_at IS NULL
                GROUP BY usm.scheme_id, sm.scheme_name
                ORDER BY mapping_order
                """, schemaName, schemaName);
        return jdbcTemplate.query(
                sql,
                (rs, n) -> new TelemetrySchemeOption(
                        toLong(rs.getObject("id")),
                        rs.getString("name")
                ),
                userId
        );
    }

    public Optional<TelemetrySchemeSelectionRecord> findLatestPendingSchemeSelectionForDate(String schemaName,
                                                                                            Long operatorId,
                                                                                            LocalDate readingDate) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id, scheme_id, correlation_id
                FROM %s.flow_reading_table
                WHERE created_by = ?
                  AND reading_date = ?
                  AND deleted_at IS NULL
                  AND COALESCE(extracted_reading, 0) = 0
                  AND COALESCE(confirmed_reading, 0) = 0
                  AND meter_change_reason IS NULL
                  AND issue_report_reason IS NULL
                  AND COALESCE(image_url, '') = ''
                  AND correlation_id LIKE ?
                ORDER BY %s DESC, id DESC
                LIMIT 1
                """, schemaName, timeColumn);
        List<TelemetrySchemeSelectionRecord> rows = jdbcTemplate.query(
                sql,
                (rs, n) -> new TelemetrySchemeSelectionRecord(
                        toLong(rs.getObject("id")),
                        toLong(rs.getObject("scheme_id")),
                        rs.getString("correlation_id")
                ),
                operatorId,
                readingDate,
                SCHEME_SELECTION_CORRELATION_PREFIX + "%"
        );
        return rows.stream().findFirst();
    }

    public String upsertPendingSchemeSelectionRecord(String schemaName,
                                                     Long schemeId,
                                                     Long operatorId,
                                                     LocalDateTime readingAt) {
        validateSchemaName(schemaName);
        LocalDate readingDate = LocalDate.from(readingAt);
        Optional<TelemetrySchemeSelectionRecord> existing = findLatestPendingSchemeSelectionForDate(
                schemaName,
                operatorId,
                readingDate
        );
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        if (existing.isPresent()) {
            String sql = String.format("""
                    UPDATE %s.flow_reading_table
                    SET scheme_id = ?,
                        %s = ?,
                        reading_date = ?,
                        updated_by = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """, schemaName, timeColumn);
            jdbcTemplate.update(sql, schemeId, readingAt, readingDate, operatorId, existing.get().id());
            return existing.get().correlationId();
        }

        String correlationId = SCHEME_SELECTION_CORRELATION_PREFIX + UUID.randomUUID();
        createFlowReading(
                schemaName,
                schemeId,
                operatorId,
                readingAt,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                correlationId,
                "",
                null
        );
        return correlationId;
    }

    public Optional<Integer> findUserLanguageId(String schemaName, Long userId) {
        validateSchemaName(schemaName);
        if (!columnExists(schemaName, "user_table", "language_id")) {
            return Optional.empty();
        }
        String sql = String.format("""
                SELECT language_id
                FROM %s.user_table
                WHERE id = ?
                LIMIT 1
                """, schemaName);
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> toInteger(rs.getObject("language_id")), userId);
        return rows.stream().findFirst();
    }

    public void updateUserLanguageId(String schemaName, Long userId, Integer languageId) {
        validateSchemaName(schemaName);
        if (!columnExists(schemaName, "user_table", "language_id")) {
            throw new IllegalStateException("Missing required column " + schemaName + ".user_table.language_id");
        }
        String sql = String.format("""
                UPDATE %s.user_table
                SET language_id = ?, updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql, languageId, userId);
    }

    public Optional<Integer> findSchemeChannel(String schemaName, Long schemeId) {
        validateSchemaName(schemaName);
        String channelColumn = resolveSelectColumn(schemaName, "scheme_master_table", "channel", "NULL::integer AS channel");
        String sql = String.format("""
                SELECT channel
                FROM %s.scheme_master_table
                WHERE id = ?
                LIMIT 1
                """, schemaName);
        sql = sql.replace("channel", channelColumn);
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> toInteger(rs.getObject("channel")), schemeId);
        return rows.stream().findFirst();
    }

    public void updateSchemeChannel(String schemaName, Long schemeId, Integer channel) {
        validateSchemaName(schemaName);
        if (!columnExists(schemaName, "scheme_master_table", "channel")) {
            throw new IllegalStateException("Missing required column " + schemaName + ".scheme_master_table.channel");
        }
        String sql = String.format("""
                UPDATE %s.scheme_master_table
                SET channel = ?, updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql, channel, schemeId);
    }

    public boolean schemeHasLatitudeAndLongitude(String schemaName, Long schemeId) {
        validateSchemaName(schemaName);
        if (schemeId == null) {
            return false;
        }

        String latColumn = resolveSelectColumn(
                schemaName,
                "scheme_master_table",
                "latitude",
                "NULL::double precision AS latitude"
        );
        String lonColumn = resolveSelectColumn(
                schemaName,
                "scheme_master_table",
                "longitude",
                "NULL::double precision AS longitude"
        );
        String sql = String.format("""
                SELECT latitude, longitude
                FROM %s.scheme_master_table
                WHERE id = ?
                LIMIT 1
                """, schemaName);
        sql = sql.replace("latitude", latColumn).replace("longitude", lonColumn);

        List<Boolean> rows = jdbcTemplate.query(sql, (rs, n) -> {
            Object lat = rs.getObject("latitude");
            Object lon = rs.getObject("longitude");
            return lat != null && lon != null;
        }, schemeId);
        return rows.stream().findFirst().orElse(false);
    }

    public boolean isOperatorMappedToScheme(String schemaName, Long operatorId, Long schemeId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT EXISTS (
                    SELECT 1
                    FROM %s.user_scheme_mapping_table
                    WHERE user_id = ?
                      AND scheme_id = ?
                      AND status = 1
                      AND deleted_at IS NULL
                )
                """, schemaName);
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, operatorId, schemeId);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<Long> findSectionOfficerUserIdForScheme(String schemaName, Long schemeId) {
        List<Long> userIds = findSectionOfficerUserIdsForScheme(schemaName, schemeId);
        return userIds.stream().findFirst();
    }

    public List<Long> findSubDivisionalOfficerUserIdsForScheme(String schemaName, Long schemeId) {
        return findUserIdsForSchemeByUserType(schemaName, schemeId, "SUB_DIVISIONAL_OFFICER");
    }

    public List<Long> findSectionOfficerUserIdsForScheme(String schemaName, Long schemeId) {
        return findUserIdsForSchemeByUserType(schemaName, schemeId, "SECTION_OFFICER");
    }

    private List<Long> findUserIdsForSchemeByUserType(String schemaName, Long schemeId, String userType) {
        validateSchemaName(schemaName);
        if (schemeId == null) {
            return List.of();
        }
        String sql = String.format("""
                SELECT usm.user_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u
                  ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                WHERE usm.scheme_id = ?
                  AND usm.status = 1
                  AND usm.deleted_at IS NULL
                  AND u.status = 1
                  AND u.deleted_at IS NULL
                  AND UPPER(COALESCE(ut.c_name, '')) = ?
                ORDER BY usm.id DESC
                """, schemaName, schemaName);
        return jdbcTemplate.query(sql, (rs, n) -> toLong(rs.getObject("user_id")), schemeId, userType);
    }

    public Long createFlowReading(String schemaName,
                                  Long schemeId,
                                  Long operatorId,
                                  LocalDateTime readingAt,
                                  BigDecimal extractedReading,
                                  BigDecimal confirmedReading,
                                  String correlationId,
                                  String imageUrl,
                                  String meterChangeReason) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String sql = hasPayloadJson
                ? String.format("""
                        INSERT INTO %s.flow_reading_table
                            (scheme_id, %s, reading_date, extracted_reading, confirmed_reading, payload_json,
                             correlation_id, quantity, channel, meter_change_reason, issue_report_reason, image_url, created_by, created_at, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, ?, jsonb_build_object('confirmed_reading', ?, 'extracted_reading', ?), ?, 0, NULL, ?, NULL, ?, ?, NOW(), ?, NOW())
                        RETURNING id
                        """, schemaName, timeColumn)
                : String.format("""
                        INSERT INTO %s.flow_reading_table
                            (scheme_id, %s, reading_date, extracted_reading, confirmed_reading,
                             correlation_id, quantity, channel, meter_change_reason, issue_report_reason, image_url, created_by, created_at, updated_by, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?, NULL, ?, ?, NOW(), ?, NOW())
                        RETURNING id
                        """, schemaName, timeColumn);

        Number id;
        if (hasPayloadJson) {
            id = jdbcTemplate.queryForObject(
                    sql,
                    Number.class,
                    schemeId,
                    readingAt,
                    LocalDate.from(readingAt),
                    extractedReading,
                    confirmedReading,
                    confirmedReading,
                    extractedReading,
                    correlationId,
                    meterChangeReason,
                    imageUrl != null ? imageUrl : "",
                    operatorId,
                    operatorId
            );
        } else {
            id = jdbcTemplate.queryForObject(
                    sql,
                    Number.class,
                    schemeId,
                    readingAt,
                    LocalDate.from(readingAt),
                    extractedReading,
                    confirmedReading,
                    correlationId,
                    meterChangeReason,
                    imageUrl != null ? imageUrl : "",
                    operatorId,
                    operatorId
            );
        }
        return id != null ? id.longValue() : null;
    }

    public Long createMeterChangeReasonRecord(String schemaName,
                                              Long schemeId,
                                              Long operatorId,
                                              LocalDateTime readingAt,
                                              String correlationId,
                                              String reason) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String sql = hasPayloadJson
                ? String.format("""
                        INSERT INTO %s.flow_reading_table
                            (scheme_id, %s, reading_date, extracted_reading, confirmed_reading, payload_json,
                             correlation_id, quantity, channel, meter_change_reason, issue_report_reason, image_url, created_by, created_at, updated_by, updated_at)
                        VALUES (?, ?, ?, 0, 0, jsonb_build_object('confirmed_reading', 0, 'extracted_reading', 0), ?, 0, NULL, ?, NULL, '', ?, NOW(), ?, NOW())
                        RETURNING id
                        """, schemaName, timeColumn)
                : String.format("""
                        INSERT INTO %s.flow_reading_table
                            (scheme_id, %s, reading_date, extracted_reading, confirmed_reading,
                             correlation_id, quantity, channel, meter_change_reason, issue_report_reason, image_url, created_by, created_at, updated_by, updated_at)
                        VALUES (?, ?, ?, 0, 0, ?, 0, NULL, ?, NULL, '', ?, NOW(), ?, NOW())
                        RETURNING id
                        """, schemaName, timeColumn);

        Number id = jdbcTemplate.queryForObject(
                sql,
                Number.class,
                schemeId,
                readingAt,
                LocalDate.from(readingAt),
                correlationId,
                reason,
                operatorId,
                operatorId
        );
        return id != null ? id.longValue() : null;
    }

    public Long createIssueReportRecord(String schemaName,
                                        Long schemeId,
                                        Long operatorId,
                                        LocalDateTime readingAt,
                                        String correlationId,
                                        String issueReason) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        LocalDate readingDate = LocalDate.from(readingAt);
        Optional<Long> existingId = findLatestFlowReadingRecordForDate(schemaName, schemeId, operatorId, readingDate);
        if (existingId.isPresent()) {
            boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
            String updateSql = hasPayloadJson
                    ? String.format("""
                            UPDATE %s.flow_reading_table
                            SET %s = ?,
                                reading_date = ?,
                                correlation_id = ?,
                                issue_report_reason = ?,
                                payload_json = jsonb_build_object(
                                    'confirmed_reading', COALESCE(confirmed_reading, 0),
                                    'extracted_reading', COALESCE(extracted_reading, 0)
                                ),
                                updated_by = ?,
                                updated_at = NOW()
                            WHERE id = ?
                            """, schemaName, timeColumn)
                    : String.format("""
                            UPDATE %s.flow_reading_table
                            SET %s = ?,
                                reading_date = ?,
                                correlation_id = ?,
                                issue_report_reason = ?,
                                updated_by = ?,
                                updated_at = NOW()
                            WHERE id = ?
                            """, schemaName, timeColumn);
            jdbcTemplate.update(updateSql, readingAt, readingDate, correlationId, issueReason, operatorId, existingId.get());
            return existingId.get();
        }

        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String insertSql = hasPayloadJson
                ? String.format("""
                        INSERT INTO %s.flow_reading_table
                            (scheme_id, %s, reading_date, extracted_reading, confirmed_reading, payload_json,
                             correlation_id, quantity, channel, meter_change_reason, issue_report_reason, image_url, created_by, created_at, updated_by, updated_at)
                        VALUES (?, ?, ?, 0, 0, jsonb_build_object('confirmed_reading', 0, 'extracted_reading', 0), ?, 0, NULL, NULL, ?, '', ?, NOW(), ?, NOW())
                        RETURNING id
                        """, schemaName, timeColumn)
                : String.format("""
                        INSERT INTO %s.flow_reading_table
                            (scheme_id, %s, reading_date, extracted_reading, confirmed_reading,
                             correlation_id, quantity, channel, meter_change_reason, issue_report_reason, image_url, created_by, created_at, updated_by, updated_at)
                        VALUES (?, ?, ?, 0, 0, ?, 0, NULL, NULL, ?, '', ?, NOW(), ?, NOW())
                        RETURNING id
                        """, schemaName, timeColumn);

        Number createdId = jdbcTemplate.queryForObject(
                insertSql,
                Number.class,
                schemeId,
                readingAt,
                readingDate,
                correlationId,
                issueReason,
                operatorId,
                operatorId
        );
        return createdId != null ? createdId.longValue() : null;
    }

    private Optional<Long> findLatestFlowReadingRecordForDate(String schemaName,
                                                              Long schemeId,
                                                              Long operatorId,
                                                              LocalDate readingDate) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date = ?
                  AND deleted_at IS NULL
                ORDER BY id DESC
                LIMIT 1
                """, schemaName);
        List<Long> rows = jdbcTemplate.query(sql, (rs, n) -> toLong(rs.getObject("id")), schemeId, operatorId, readingDate);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryPendingMeterChangeRecord> findLatestPendingMeterChangeRecord(String schemaName, Long schemeId, Long operatorId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by, extracted_reading
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND extracted_reading = 0
                  AND confirmed_reading = 0
                  AND meter_change_reason IS NOT NULL
                  AND deleted_at IS NULL
                ORDER BY id DESC
                LIMIT 1
                """, schemaName);
        List<TelemetryPendingMeterChangeRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryPendingMeterChangeRecord(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by")),
                        rs.getBigDecimal("extracted_reading")
                ), schemeId, operatorId);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryPendingIssueReportRecord> findLatestPendingIssueReportRecord(String schemaName, Long schemeId, Long operatorId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND extracted_reading = 0
                  AND confirmed_reading = 0
                  AND issue_report_reason IS NOT NULL
                  AND deleted_at IS NULL
                ORDER BY id DESC
                LIMIT 1
                """, schemaName);
        List<TelemetryPendingIssueReportRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryPendingIssueReportRecord(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by"))
                ), schemeId, operatorId);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryPendingMeterChangeRecord> findPendingMeterChangeRecordByCorrelation(String schemaName,
                                                                                                  Long schemeId,
                                                                                                  Long operatorId,
                                                                                                  String correlationId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by, extracted_reading
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND correlation_id = ?
                  AND extracted_reading = 0
                  AND confirmed_reading = 0
                  AND meter_change_reason IS NOT NULL
                  AND deleted_at IS NULL
                LIMIT 1
                """, schemaName);
        List<TelemetryPendingMeterChangeRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryPendingMeterChangeRecord(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by")),
                        rs.getBigDecimal("extracted_reading")
                ), schemeId, operatorId, correlationId);
        return rows.stream().findFirst();
    }

    public String upsertPendingMeterChangeRecord(String schemaName,
                                                 Long schemeId,
                                                 Long operatorId,
                                                 LocalDateTime readingAt,
                                                 String reason) {
        Optional<TelemetryPendingMeterChangeRecord> pending = findLatestPendingMeterChangeRecord(schemaName, schemeId, operatorId);
        if (pending.isPresent()) {
            String timeColumn = resolveFlowReadingTimeColumn(schemaName);
            String sql = String.format("""
                    UPDATE %s.flow_reading_table
                    SET %s = ?,
                        reading_date = ?,
                        meter_change_reason = ?,
                        updated_by = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """, schemaName, timeColumn);
            jdbcTemplate.update(sql, readingAt, LocalDate.from(readingAt), reason, operatorId, pending.get().id());
            cleanupOtherPendingMeterChangeRecords(schemaName, schemeId, operatorId, pending.get().id(), operatorId);
            return pending.get().correlationId();
        }

        String correlationId = "meter-change-" + UUID.randomUUID();
        Long createdId = createMeterChangeReasonRecord(schemaName, schemeId, operatorId, readingAt, correlationId, reason);
        cleanupOtherPendingMeterChangeRecords(schemaName, schemeId, operatorId, createdId, operatorId);
        return correlationId;
    }

    public String upsertPendingIssueReportRecord(String schemaName,
                                                 Long schemeId,
                                                 Long operatorId,
                                                 LocalDateTime readingAt,
                                                 String reason) {
        Optional<TelemetryPendingIssueReportRecord> pending = findLatestPendingIssueReportRecord(schemaName, schemeId, operatorId);
        if (pending.isPresent()) {
            String timeColumn = resolveFlowReadingTimeColumn(schemaName);
            String sql = String.format("""
                    UPDATE %s.flow_reading_table
                    SET %s = ?,
                        reading_date = ?,
                        issue_report_reason = ?,
                        updated_by = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """, schemaName, timeColumn);
            jdbcTemplate.update(sql, readingAt, LocalDate.from(readingAt), reason, operatorId, pending.get().id());
            cleanupOtherPendingIssueReportRecords(schemaName, schemeId, operatorId, pending.get().id(), operatorId);
            return pending.get().correlationId();
        }

        String correlationId = "issue-report-" + UUID.randomUUID();
        Long createdId = createIssueReportRecord(schemaName, schemeId, operatorId, readingAt, correlationId, reason);
        cleanupOtherPendingIssueReportRecords(schemaName, schemeId, operatorId, createdId, operatorId);
        return correlationId;
    }

    private void cleanupOtherPendingMeterChangeRecords(String schemaName,
                                                       Long schemeId,
                                                       Long operatorId,
                                                       Long keepId,
                                                       Long updatedBy) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                UPDATE %s.flow_reading_table
                SET deleted_at = NOW(),
                    deleted_by = ?,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND extracted_reading = 0
                  AND confirmed_reading = 0
                  AND meter_change_reason IS NOT NULL
                  AND deleted_at IS NULL
                  AND id <> ?
                """, schemaName);
        jdbcTemplate.update(sql, updatedBy, updatedBy, schemeId, operatorId, keepId);
    }

    private void cleanupOtherPendingIssueReportRecords(String schemaName,
                                                       Long schemeId,
                                                       Long operatorId,
                                                       Long keepId,
                                                       Long updatedBy) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                UPDATE %s.flow_reading_table
                SET deleted_at = NOW(),
                    deleted_by = ?,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND extracted_reading = 0
                  AND confirmed_reading = 0
                  AND issue_report_reason IS NOT NULL
                  AND deleted_at IS NULL
                  AND id <> ?
                """, schemaName);
        jdbcTemplate.update(sql, updatedBy, updatedBy, schemeId, operatorId, keepId);
    }

    public void updatePendingMeterChangeReading(String schemaName,
                                                Long readingId,
                                                BigDecimal readingValue,
                                                Long updatedBy) {
        validateSchemaName(schemaName);
        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String sql = hasPayloadJson
                ? String.format("""
                        UPDATE %s.flow_reading_table
                        SET extracted_reading = ?,
                            confirmed_reading = ?,
                            payload_json = jsonb_build_object('confirmed_reading', ?, 'extracted_reading', ?),
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName)
                : String.format("""
                        UPDATE %s.flow_reading_table
                        SET extracted_reading = ?,
                            confirmed_reading = ?,
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName);
        if (hasPayloadJson) {
            jdbcTemplate.update(sql, readingValue, readingValue, readingValue, readingValue, updatedBy, readingId);
        } else {
            jdbcTemplate.update(sql, readingValue, readingValue, updatedBy, readingId);
        }
    }

    public void updateMeterChangeReason(String schemaName,
                                        Long readingId,
                                        String meterChangeReason,
                                        Long updatedBy) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                UPDATE %s.flow_reading_table
                SET meter_change_reason = ?,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql, meterChangeReason, updatedBy, readingId);
    }

    public Optional<BigDecimal> findLastConfirmedReading(String schemaName, Long schemeId, Long excludeReadingId) {
        return findLatestConfirmedReadingSnapshot(schemaName, schemeId, excludeReadingId)
                .map(TelemetryConfirmedReadingSnapshot::confirmedReading);
    }

    public Optional<TelemetryConfirmedReadingSnapshot> findLatestConfirmedReadingSnapshot(String schemaName,
                                                                                          Long schemeId,
                                                                                          Long excludeReadingId) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        StringBuilder sql = new StringBuilder(String.format("""
                SELECT confirmed_reading, created_at
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND confirmed_reading > 0
                  AND deleted_at IS NULL
                """, schemaName));
        List<Object> params = new ArrayList<>();
        params.add(schemeId);
        if (excludeReadingId != null) {
            sql.append(" AND id <> ?");
            params.add(excludeReadingId);
        }
        sql.append(" ORDER BY ").append(timeColumn).append(" DESC, created_at DESC LIMIT 1");
        List<TelemetryConfirmedReadingSnapshot> rows = jdbcTemplate.query(
                sql.toString(),
                (rs, n) -> new TelemetryConfirmedReadingSnapshot(
                        rs.getBigDecimal("confirmed_reading"),
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
                ),
                params.toArray()
        );
        return rows.stream().findFirst();
    }

    /**
     * Returns the latest confirmed reading strictly before {@code cutoffDateExclusive}.
     * This is useful when validations should ignore any readings submitted "today".
     */
    public Optional<TelemetryConfirmedReadingSnapshot> findLatestConfirmedReadingSnapshotBeforeDate(String schemaName,
                                                                                                    Long schemeId,
                                                                                                    LocalDate cutoffDateExclusive,
                                                                                                    Long excludeReadingId) {
        validateSchemaName(schemaName);
        if (cutoffDateExclusive == null) {
            throw new IllegalArgumentException("cutoffDateExclusive is required");
        }
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        LocalDateTime cutoffTimeExclusive = cutoffDateExclusive.atStartOfDay();
        StringBuilder sql = new StringBuilder(String.format("""
                SELECT confirmed_reading, created_at
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND confirmed_reading > 0
                  AND %s < ?
                  AND deleted_at IS NULL
                """, schemaName, timeColumn));
        List<Object> params = new ArrayList<>();
        params.add(schemeId);
        params.add(cutoffTimeExclusive);
        if (excludeReadingId != null) {
            sql.append(" AND id <> ?");
            params.add(excludeReadingId);
        }
        sql.append(" ORDER BY ").append(timeColumn).append(" DESC, created_at DESC LIMIT 1");
        List<TelemetryConfirmedReadingSnapshot> rows = jdbcTemplate.query(
                sql.toString(),
                (rs, n) -> new TelemetryConfirmedReadingSnapshot(
                        rs.getBigDecimal("confirmed_reading"),
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
                ),
                params.toArray()
        );
        return rows.stream().findFirst();
    }

    /**
     * Returns the latest confirmed reading for the given {@code readingDate}, based on the tenant's flow-reading
     * timestamp column (prefer {@code observation_time}, fallback to {@code reading_at}).
     * If there are no readings for that date, returns empty.
     */
    public Optional<TelemetryConfirmedReadingSnapshot> findLatestConfirmedReadingSnapshotForDate(String schemaName,
                                                                                                 Long schemeId,
                                                                                                 LocalDate readingDate,
                                                                                                 Long excludeReadingId) {
        validateSchemaName(schemaName);
        if (readingDate == null) {
            throw new IllegalArgumentException("readingDate is required");
        }
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        LocalDateTime startInclusive = readingDate.atStartOfDay();
        LocalDateTime endExclusive = readingDate.plusDays(1).atStartOfDay();
        StringBuilder sql = new StringBuilder(String.format("""
                SELECT confirmed_reading, created_at
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND confirmed_reading > 0
                  AND %s >= ?
                  AND %s < ?
                  AND deleted_at IS NULL
                """, schemaName, timeColumn, timeColumn));
        List<Object> params = new ArrayList<>();
        params.add(schemeId);
        params.add(startInclusive);
        params.add(endExclusive);
        if (excludeReadingId != null) {
            sql.append(" AND id <> ?");
            params.add(excludeReadingId);
        }
        sql.append(" ORDER BY ").append(timeColumn).append(" DESC, created_at DESC LIMIT 1");
        List<TelemetryConfirmedReadingSnapshot> rows = jdbcTemplate.query(
                sql.toString(),
                (rs, n) -> new TelemetryConfirmedReadingSnapshot(
                        rs.getBigDecimal("confirmed_reading"),
                        rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
                ),
                params.toArray()
        );
        return rows.stream().findFirst();
    }

    public int countAnomaliesByTypeForToday(String schemaName, Long userId, Long schemeId, int anomalyType) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT COUNT(1)
                FROM %s.anomaly_table
                WHERE user_id = ?
                  AND scheme_id = ?
                  AND type = ?
                  AND DATE(created_at) = CURRENT_DATE
                  AND deleted_at IS NULL
                """, schemaName);
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId, schemeId, anomalyType);
        return count != null ? count : 0;
    }

    public List<LocalDate> findAnomalyDatesByType(String schemaName, Long userId, Long schemeId, int anomalyType, int limitDays) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT DATE(created_at) AS reading_date
                FROM %s.anomaly_table
                WHERE user_id = ?
                  AND scheme_id = ?
                  AND type = ?
                  AND deleted_at IS NULL
                GROUP BY DATE(created_at)
                ORDER BY reading_date DESC
                LIMIT ?
                """, schemaName);
        return jdbcTemplate.query(
                sql,
                (rs, n) -> rs.getDate("reading_date").toLocalDate(),
                userId,
                schemeId,
                anomalyType,
                Math.max(limitDays, 1)
        );
    }

    public void createTenantAnomalyRecord(String schemaName,
                                          Long userId,
                                          Long schemeId,
                                          Integer type,
                                          String reason,
                                          Integer status) {
        validateSchemaName(schemaName);
        boolean hasDetail = columnExists(schemaName, "anomaly_table", "detail");
        boolean hasReason = columnExists(schemaName, "anomaly_table", "reason");

        String sql;
        if (hasDetail && hasReason) {
            sql = String.format("""
                    INSERT INTO %s.anomaly_table
                        (user_id, scheme_id, type, reason, detail, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW())
                    """, schemaName);
            jdbcTemplate.update(sql, userId, schemeId, type, reason, reason, status);
        } else if (hasDetail) {
            sql = String.format("""
                    INSERT INTO %s.anomaly_table
                        (user_id, scheme_id, type, detail, status, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    """, schemaName);
            jdbcTemplate.update(sql, userId, schemeId, type, reason, status);
        } else {
            sql = String.format("""
                    INSERT INTO %s.anomaly_table
                        (user_id, scheme_id, type, reason, status, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    """, schemaName);
            jdbcTemplate.update(sql, userId, schemeId, type, reason, status);
        }
    }

    public Optional<TelemetryReadingRecord> findReadingByCorrelationId(String schemaName, String correlationId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by
                FROM %s.flow_reading_table
                WHERE correlation_id = ?
                LIMIT 1
                """, schemaName);
        List<TelemetryReadingRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryReadingRecord(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by"))
                ), correlationId);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryFlowReadingDetails> findLatestFlowReadingForDate(String schemaName,
                                                                              Long schemeId,
                                                                              Long operatorId,
                                                                              LocalDate readingDate) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by, extracted_reading, confirmed_reading
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date = ?
                  AND deleted_at IS NULL
                ORDER BY %s DESC, id DESC
                LIMIT 1
                """, schemaName, timeColumn);
        List<TelemetryFlowReadingDetails> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryFlowReadingDetails(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by")),
                        rs.getBigDecimal("extracted_reading"),
                        rs.getBigDecimal("confirmed_reading")
                ), schemeId, operatorId, readingDate);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryReadingRecord> findLatestCompletedReadingForToday(String schemaName,
                                                                                Long schemeId,
                                                                                Long operatorId) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date = CURRENT_DATE
                  AND extracted_reading > 0
                  AND confirmed_reading > 0
                  AND deleted_at IS NULL
                ORDER BY %s DESC, id DESC
                LIMIT 1
                """, schemaName, timeColumn);
        List<TelemetryReadingRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryReadingRecord(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by"))
                ), schemeId, operatorId);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryReadingRecord> findLatestCompletedReadingForPreviousDay(String schemaName,
                                                                                      Long schemeId,
                                                                                      Long operatorId) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date = (CURRENT_DATE - INTERVAL '1 day')::date
                  AND extracted_reading > 0
                  AND confirmed_reading > 0
                  AND deleted_at IS NULL
                ORDER BY %s DESC, id DESC
                LIMIT 1
                """, schemaName, timeColumn);
        List<TelemetryReadingRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TelemetryReadingRecord(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by"))
                ), schemeId, operatorId);
        return rows.stream().findFirst();
    }

    public Optional<TelemetryCompletedFlowReading> findLatestCompletedFlowReadingBeforeDate(String schemaName,
                                                                                             Long schemeId,
                                                                                             Long operatorId,
                                                                                             LocalDate beforeDate) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by, reading_date, confirmed_reading
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date < ?
                  AND extracted_reading > 0
                  AND confirmed_reading > 0
                  AND deleted_at IS NULL
                ORDER BY reading_date DESC, %s DESC, id DESC
                LIMIT 1
                """, schemaName, timeColumn);
        List<TelemetryCompletedFlowReading> rows = jdbcTemplate.query(
                sql,
                (rs, n) -> new TelemetryCompletedFlowReading(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by")),
                        rs.getObject("reading_date", LocalDate.class),
                        rs.getBigDecimal("confirmed_reading")
                ),
                schemeId,
                operatorId,
                beforeDate
        );
        return rows.stream().findFirst();
    }

    public Optional<TelemetryCompletedFlowReading> findEarliestCompletedFlowReadingAfterDate(String schemaName,
                                                                                              Long schemeId,
                                                                                              Long operatorId,
                                                                                              LocalDate afterDate) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id, correlation_id, created_by, reading_date, confirmed_reading
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date > ?
                  AND extracted_reading > 0
                  AND confirmed_reading > 0
                  AND deleted_at IS NULL
                ORDER BY reading_date ASC, %s ASC, id ASC
                LIMIT 1
                """, schemaName, timeColumn);
        List<TelemetryCompletedFlowReading> rows = jdbcTemplate.query(
                sql,
                (rs, n) -> new TelemetryCompletedFlowReading(
                        toLong(rs.getObject("id")),
                        rs.getString("correlation_id"),
                        toLong(rs.getObject("created_by")),
                        rs.getObject("reading_date", LocalDate.class),
                        rs.getBigDecimal("confirmed_reading")
                ),
                schemeId,
                operatorId,
                afterDate
        );
        return rows.stream().findFirst();
    }

    public void updateReadingValues(String schemaName, Long readingId, BigDecimal readingValue, Long updatedBy) {
        validateSchemaName(schemaName);
        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String sql = hasPayloadJson
                ? String.format("""
                        UPDATE %s.flow_reading_table
                        SET extracted_reading = ?,
                            confirmed_reading = ?,
                            payload_json = jsonb_build_object('confirmed_reading', ?, 'extracted_reading', ?),
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName)
                : String.format("""
                        UPDATE %s.flow_reading_table
                        SET extracted_reading = ?,
                            confirmed_reading = ?,
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName);
        if (hasPayloadJson) {
            jdbcTemplate.update(sql, readingValue, readingValue, readingValue, readingValue, updatedBy, readingId);
        } else {
            jdbcTemplate.update(sql, readingValue, readingValue, updatedBy, readingId);
        }
    }

    public void updateConfirmedReading(String schemaName, Long readingId, BigDecimal confirmedReading, Long updatedBy) {
        validateSchemaName(schemaName);
        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String sql = hasPayloadJson
                ? String.format("""
                        UPDATE %s.flow_reading_table
                        SET confirmed_reading = ?,
                            payload_json = jsonb_build_object('confirmed_reading', ?, 'extracted_reading', COALESCE(extracted_reading, 0)),
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName)
                : String.format("""
                        UPDATE %s.flow_reading_table
                        SET confirmed_reading = ?, updated_by = ?, updated_at = NOW()
                        WHERE id = ?
                        """, schemaName);
        if (hasPayloadJson) {
            jdbcTemplate.update(sql, confirmedReading, confirmedReading, updatedBy, readingId);
        } else {
            jdbcTemplate.update(sql, confirmedReading, updatedBy, readingId);
        }
    }

    public void updateReadingLocation(String schemaName,
                                      Long readingId,
                                      BigDecimal latitude,
                                      BigDecimal longitude,
                                      Long updatedBy) {
        validateSchemaName(schemaName);
        if (!columnExists(schemaName, "flow_reading_table", "latitude")) {
            throw new IllegalStateException("Missing required column " + schemaName + ".flow_reading_table.latitude");
        }
        if (!columnExists(schemaName, "flow_reading_table", "longitude")) {
            throw new IllegalStateException("Missing required column " + schemaName + ".flow_reading_table.longitude");
        }

        String sql = String.format("""
                UPDATE %s.flow_reading_table
                SET latitude = ?,
                    longitude = ?,
                    updated_by = ?,
                    updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql, latitude, longitude, updatedBy, readingId);
    }

    public void upsertAnalyticsWaterQuantity(Integer tenantId,
                                             Long schemeId,
                                             Long userId,
                                             LocalDate date,
                                             BigDecimal waterQuantity,
                                             Integer submissionStatus) {
        if (tenantId == null || schemeId == null || userId == null || date == null || waterQuantity == null) {
            throw new IllegalArgumentException("tenantId, schemeId, userId, date, and waterQuantity are required");
        }

        int schemeIdInt = Math.toIntExact(schemeId);
        int userIdInt = Math.toIntExact(userId);
        int waterQuantityInt = Math.max(0, waterQuantity.setScale(0, RoundingMode.HALF_UP).intValue());

        boolean hasSubmissionStatus = columnExists("analytics_schema", "fact_water_quantity_table", "submission_status");
        boolean hasOutageReason = columnExists("analytics_schema", "fact_water_quantity_table", "outage_reason");
        boolean hasNonSubmissionReason = columnExists("analytics_schema", "fact_water_quantity_table", "non_submission_reason");

        List<Object> updateArgs = new ArrayList<>();
        updateArgs.add(tenantId);
        updateArgs.add(schemeIdInt);
        updateArgs.add(date);
        StringBuilder updateSql = new StringBuilder("""
                UPDATE analytics_schema.fact_water_quantity_table
                SET user_id = ?,
                    water_quantity = ?,
                    updated_at = NOW()
                """);
        updateArgs.add(userIdInt);
        updateArgs.add(waterQuantityInt);
        if (hasSubmissionStatus) {
            updateSql.append(", submission_status = ?");
            updateArgs.add(submissionStatus);
        }
        if (hasOutageReason) {
            updateSql.append(", outage_reason = NULL");
        }
        if (hasNonSubmissionReason) {
            updateSql.append(", non_submission_reason = NULL");
        }
        updateSql.insert(0, """
                WITH latest AS (
                    SELECT id
                    FROM analytics_schema.fact_water_quantity_table
                    WHERE tenant_id = ?
                      AND scheme_id = ?
                      AND "date" = ?
                    ORDER BY updated_at DESC NULLS LAST, id DESC
                    LIMIT 1
                )
                """);
        updateSql.append("""

                FROM latest
                WHERE analytics_schema.fact_water_quantity_table.id = latest.id
                """);

        int updated = jdbcTemplate.update(updateSql.toString(), updateArgs.toArray());
        if (updated > 0) {
            return;
        }

        List<Object> insertArgs = new ArrayList<>();
        StringBuilder columns = new StringBuilder("tenant_id, scheme_id, user_id, water_quantity, \"date\", created_at, updated_at");
        StringBuilder values = new StringBuilder("?, ?, ?, ?, ?, NOW(), NOW()");
        insertArgs.add(tenantId);
        insertArgs.add(schemeIdInt);
        insertArgs.add(userIdInt);
        insertArgs.add(waterQuantityInt);
        insertArgs.add(date);
        if (hasSubmissionStatus) {
            columns.append(", submission_status");
            values.append(", ?");
            insertArgs.add(submissionStatus);
        }
        if (hasOutageReason) {
            columns.append(", outage_reason");
            values.append(", NULL");
        }
        if (hasNonSubmissionReason) {
            columns.append(", non_submission_reason");
            values.append(", NULL");
        }

        String insertSql = "INSERT INTO analytics_schema.fact_water_quantity_table (" + columns + ") VALUES (" + values + ")";
        jdbcTemplate.update(insertSql, insertArgs.toArray());
    }

    public Optional<Long> findLatestPlaceholderFlowReadingIdForDate(String schemaName,
                                                                    Long schemeId,
                                                                    Long operatorId,
                                                                    LocalDate readingDate) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        String sql = String.format("""
                SELECT id
                FROM %s.flow_reading_table
                WHERE scheme_id = ?
                  AND created_by = ?
                  AND reading_date = ?
                  AND deleted_at IS NULL
                  AND COALESCE(extracted_reading, 0) = 0
                  AND COALESCE(confirmed_reading, 0) = 0
                  AND meter_change_reason IS NULL
                  AND issue_report_reason IS NULL
                  AND COALESCE(image_url, '') = ''
                ORDER BY %s DESC, id DESC
                LIMIT 1
                """, schemaName, timeColumn);
        List<Long> rows = jdbcTemplate.query(sql, (rs, n) -> toLong(rs.getObject("id")), schemeId, operatorId, readingDate);
        return rows.stream().findFirst();
    }

    public void updateFlowReadingFromIngestion(String schemaName,
                                               Long readingId,
                                               LocalDateTime readingAt,
                                               BigDecimal extractedReading,
                                               BigDecimal confirmedReading,
                                               String correlationId,
                                               String imageUrl,
                                               String meterChangeReason,
                                               Long updatedBy) {
        validateSchemaName(schemaName);
        String timeColumn = resolveFlowReadingTimeColumn(schemaName);
        boolean hasPayloadJson = columnExists(schemaName, "flow_reading_table", "payload_json");
        String sql = hasPayloadJson
                ? String.format("""
                        UPDATE %s.flow_reading_table
                        SET %s = ?,
                            reading_date = ?,
                            extracted_reading = ?,
                            confirmed_reading = ?,
                            payload_json = jsonb_build_object('confirmed_reading', ?, 'extracted_reading', ?),
                            correlation_id = ?,
                            image_url = ?,
                            meter_change_reason = ?,
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName, timeColumn)
                : String.format("""
                        UPDATE %s.flow_reading_table
                        SET %s = ?,
                            reading_date = ?,
                            extracted_reading = ?,
                            confirmed_reading = ?,
                            correlation_id = ?,
                            image_url = ?,
                            meter_change_reason = ?,
                            updated_by = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, schemaName, timeColumn);
        if (hasPayloadJson) {
            jdbcTemplate.update(
                    sql,
                    readingAt,
                    LocalDate.from(readingAt),
                    extractedReading,
                    confirmedReading,
                    confirmedReading,
                    extractedReading,
                    correlationId,
                    imageUrl != null ? imageUrl : "",
                    meterChangeReason,
                    updatedBy,
                    readingId
            );
        } else {
            jdbcTemplate.update(
                    sql,
                    readingAt,
                    LocalDate.from(readingAt),
                    extractedReading,
                    confirmedReading,
                    correlationId,
                    imageUrl != null ? imageUrl : "",
                    meterChangeReason,
                    updatedBy,
                    readingId
            );
        }
    }

    private Optional<TelemetryOperator> findOperatorByPhone(String schemaName, String rawPhoneNumber, String normalizedPhone) {
        validateSchemaName(schemaName);
        if (columnExists(schemaName, "user_table", "phone_number_hash")) {
            Optional<TelemetryOperator> hashMatch = findOperatorByPhoneHash(schemaName, rawPhoneNumber);
            if (hashMatch.isPresent()) {
                return hashMatch;
            }
            if (normalizedPhone != null && !normalizedPhone.isBlank() && !normalizedPhone.equals(rawPhoneNumber)) {
                hashMatch = findOperatorByPhoneHash(schemaName, normalizedPhone);
                if (hashMatch.isPresent()) {
                    return hashMatch;
                }
            }
        }
        String languageColumn = resolveSelectColumn(schemaName, "user_table", "language_id", "NULL::integer AS language_id");
        String sql = String.format("""
                SELECT id, tenant_id, title, email, phone_number, language_id
                FROM %s.user_table
                WHERE phone_number = ?
                   OR regexp_replace(COALESCE(phone_number, ''), '\\\\D', '', 'g') = ?
                LIMIT 1
                """, schemaName);
        sql = sql.replace("language_id", languageColumn);
        List<TelemetryOperator> rows = jdbcTemplate.query(sql, (rs, n) -> mapOperator(rs), rawPhoneNumber, normalizedPhone);
        Optional<TelemetryOperator> directMatch = rows.stream().findFirst();
        if (directMatch.isPresent()) {
            return directMatch;
        }

        String scanSql = String.format("""
                SELECT id, tenant_id, title, email, phone_number, language_id
                FROM %s.user_table
                """, schemaName);
        scanSql = scanSql.replace("language_id", languageColumn);
        List<TelemetryOperator> allRows = jdbcTemplate.query(scanSql, (rs, n) -> mapOperator(rs));
        String normalizedLast10 = normalizeToLast10(normalizedPhone);
        for (TelemetryOperator operator : allRows) {
            String candidate = normalizePhone(operator.phoneNumber());
            if (candidate == null) {
                continue;
            }
            if (candidate.equals(normalizedPhone)) {
                return Optional.of(operator);
            }
            if (normalizedLast10 != null && normalizedLast10.equals(candidate)) {
                return Optional.of(operator);
            }
            String candidateLast10 = normalizeToLast10(candidate);
            if (candidateLast10 != null && candidateLast10.equals(normalizedPhone)) {
                return Optional.of(operator);
            }
            if (normalizedLast10 != null && candidateLast10 != null && candidateLast10.equals(normalizedLast10)) {
                return Optional.of(operator);
            }
        }
        return Optional.empty();
    }

    private Optional<TelemetryOperator> findOperatorByPhoneHash(String schemaName, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }
        String languageColumn = resolveSelectColumn(schemaName, "user_table", "language_id", "NULL::integer AS language_id");
        String sql = String.format("""
                SELECT id, tenant_id, title, email, phone_number, language_id
                FROM %s.user_table
                WHERE phone_number_hash = ?
                LIMIT 1
                """, schemaName);
        sql = sql.replace("language_id", languageColumn);
        for (String candidate : buildPhoneCandidates(phoneNumber)) {
            String lookupHash = piiEncryptionService.hmac(candidate);
            List<TelemetryOperator> rows = jdbcTemplate.query(sql, (rs, n) -> mapOperator(rs), lookupHash);
            Optional<TelemetryOperator> match = rows.stream().findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private Optional<TelemetryOperator> findOperatorByPhoneHashValue(String schemaName, String phoneHash) {
        validateSchemaName(schemaName);
        if (phoneHash == null || phoneHash.isBlank()) {
            return Optional.empty();
        }
        if (!columnExists(schemaName, "user_table", "phone_number_hash")) {
            return Optional.empty();
        }
        String languageColumn = resolveSelectColumn(schemaName, "user_table", "language_id", "NULL::integer AS language_id");
        String sql = String.format("""
                SELECT id, tenant_id, title, email, phone_number, language_id
                FROM %s.user_table
                WHERE phone_number_hash = ?
                LIMIT 1
                """, schemaName);
        sql = sql.replace("language_id", languageColumn);
        List<TelemetryOperator> rows = jdbcTemplate.query(sql, (rs, n) -> mapOperator(rs), phoneHash);
        return rows.stream().findFirst();
    }

    private TelemetryOperator mapOperator(ResultSet rs) {
        try {
            return new TelemetryOperator(
                    toLong(rs.getObject("id")),
                    toInteger(rs.getObject("tenant_id")),
                    decryptIfNeeded(rs.getString("title")),
                    decryptIfNeeded(rs.getString("email")),
                    decryptPhoneIfNeeded(rs.getString("phone_number")),
                    toInteger(rs.getObject("language_id"))
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to map operator record", ex);
        }
    }

    private String decryptPhoneIfNeeded(String value) {
        return piiEncryptionService.safeDecrypt(value);
    }

    private String decryptIfNeeded(String value) {
        return piiEncryptionService.safeDecrypt(value);
    }

    private String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\D", "");
    }

    private String normalizeToLast10(String digits) {
        if (digits == null || digits.isBlank()) {
            return null;
        }
        String normalized = digits.replaceAll("\\D", "");
        if (normalized.length() <= 10) {
            return normalized;
        }
        return normalized.substring(normalized.length() - 10);
    }

    private List<String> buildPhoneCandidates(String rawPhone) {
        if (rawPhone == null) {
            return List.of();
        }
        String trimmed = rawPhone.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(trimmed);
        if (trimmed.startsWith("+") && trimmed.length() > 1) {
            candidates.add(trimmed.substring(1));
        } else if (!trimmed.startsWith("+")) {
            candidates.add("+" + trimmed);
        }

        String normalized = normalizePhone(trimmed);
        if (normalized != null && !normalized.isBlank()) {
            candidates.add(normalized);
            String last10 = normalizeToLast10(normalized);
            if (last10 != null && !last10.equals(normalized)) {
                candidates.add(last10);
            }
            if (normalized.length() == 10) {
                candidates.add("91" + normalized);
                candidates.add("+91" + normalized);
            }
            if (normalized.length() == 12 && normalized.startsWith("91")) {
                candidates.add(normalized.substring(2));
                candidates.add("+91" + normalized.substring(2));
            }
        }

        List<String> deduped = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (!deduped.contains(candidate)) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    public void invalidateMetadataCaches() {
        synchronized (metadataCacheLock) {
            tenantSchemasCache = List.of();
            tenantSchemasCacheExpiresAtMs = 0L;
            columnExistsCache.clear();
        }
    }

    private boolean columnExists(String schemaName, String tableName, String columnName) {
        if (!metadataCacheEnabled || columnExistsCacheTtlMs <= 0L) {
            return queryColumnExists(schemaName, tableName, columnName);
        }
        String cacheKey = schemaName + "." + tableName + "." + columnName;
        long now = System.currentTimeMillis();
        TimedCacheValue<Boolean> cached = columnExistsCache.get(cacheKey);
        if (cached != null && !cached.isExpired(now)) {
            return cached.value();
        }
        boolean exists = queryColumnExists(schemaName, tableName, columnName);
        columnExistsCache.put(cacheKey, new TimedCacheValue<>(exists, now + columnExistsCacheTtlMs));
        return exists;
    }

    private boolean queryColumnExists(String schemaName, String tableName, String columnName) {
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

    private List<String> getTenantSchemasCached() {
        if (!metadataCacheEnabled || tenantSchemaListCacheTtlMs <= 0L) {
            return queryTenantSchemas();
        }
        long now = System.currentTimeMillis();
        if (now < tenantSchemasCacheExpiresAtMs) {
            return tenantSchemasCache;
        }
        synchronized (metadataCacheLock) {
            long refreshedNow = System.currentTimeMillis();
            if (refreshedNow < tenantSchemasCacheExpiresAtMs) {
                return tenantSchemasCache;
            }
            List<String> refreshed = queryTenantSchemas();
            tenantSchemasCache = refreshed;
            tenantSchemasCacheExpiresAtMs = refreshedNow + tenantSchemaListCacheTtlMs;
            return refreshed;
        }
    }

    private List<String> queryTenantSchemas() {
        String schemaSql = """
                SELECT nspname
                FROM pg_namespace
                WHERE nspname LIKE 'tenant_%'
                ORDER BY nspname
                """;
        return jdbcTemplate.query(schemaSql, (rs, n) -> rs.getString("nspname"));
    }

    private String resolveSelectColumn(String schemaName, String tableName, String columnName, String fallbackExpression) {
        return columnExists(schemaName, tableName, columnName) ? columnName : fallbackExpression;
    }

    /**
     * flow_reading_table time column differs across tenant schema versions:
     * - legacy: reading_at
     * - newer:  observation_time (NOT NULL)
     *
     * Prefer observation_time when available to satisfy NOT NULL constraints and keep ordering consistent.
     */
    private String resolveFlowReadingTimeColumn(String schemaName) {
        return columnExists(schemaName, "flow_reading_table", "observation_time") ? "observation_time" : "reading_at";
    }

    private Optional<String> findSchemaByTenantId(Integer tenantId) {
        String sql = """
                SELECT state_code
                FROM common_schema.tenant_master_table
                WHERE id = ?
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getString("state_code"), tenantId);
        return rows.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> "tenant_" + code.trim().toLowerCase())
                .findFirst();
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Expected numeric DB value, got: " + value.getClass().getName());
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Expected numeric DB value, got: " + value.getClass().getName());
    }

    private record TimedCacheValue<T>(T value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
