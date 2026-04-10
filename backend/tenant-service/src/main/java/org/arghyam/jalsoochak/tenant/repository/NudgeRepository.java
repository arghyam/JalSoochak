package org.arghyam.jalsoochak.tenant.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.tenant.service.PiiEncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * JdbcTemplate-based repository for nudge and escalation queries.
 *
 * <p><b>Security note (java:S2077):</b> PostgreSQL schema names are SQL <em>identifiers</em>,
 * not values, so they cannot be supplied via JDBC bind parameters ({@code ?}).
 * Every public method calls {@link #validateSchemaName(String)} before the schema name
 * is interpolated into SQL via {@code String.format}. The regex
 * {@code ^[a-z_][a-z0-9_]*$} ensures only safe, lowercase alphanumeric characters
 * reach the query. All runtime data values (dates, IDs, counts) are bound as
 * {@code ?} parameters and never concatenated into SQL.</p>
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
@Slf4j
public class NudgeRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService pii;

    /**
     * Streams OPERATOR users who have an active scheme mapping but no flow reading for today,
     * calling {@code consumer} once per row. Returns the total row count.
     *
     * <p>Uses a server-side cursor (fetchSize=500) to avoid materialising the full result set
     * into heap, preventing OOM on large tenants.</p>
     */
    @Transactional(readOnly = true)
    public int streamUsersWithNoUploadToday(String schema, LocalDate referenceDate,
                                            Consumer<Map<String, Object>> consumer) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT u.id as user_id, u.title as name, u.phone_number, u.language_id,
                       u.whatsapp_connection_id, usm.scheme_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                LEFT JOIN %s.flow_reading_table fr
                    ON fr.scheme_id = usm.scheme_id
                    AND fr.created_by = u.id
                    AND fr.reading_date = ?
                WHERE usm.status = 1
                  AND u.status = 1
                  AND UPPER(ut.c_name) = 'PUMP_OPERATOR'
                  AND fr.id IS NULL
                """, schema, schema, schema);
        log.debug("streamUsersWithNoUploadToday – schema={}", schema);
        int[] count = {0};
        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setObject(1, referenceDate);
            ps.setFetchSize(500);
            return ps;
        }, rs -> {
            Object userId = rs.getObject("user_id");
            String rawName = rs.getString("name");
            String rawPhone = rs.getString("phone_number");
            String name = decryptPii(rawName, "name", userId, schema);
            String phoneNumber = decryptPii(rawPhone, "phone_number", userId, schema);
            if ((rawName != null && name == null) || (rawPhone != null && phoneNumber == null)) return;
            Map<String, Object> row = new HashMap<>(8);
            row.put("user_id", userId);
            row.put("name", name);
            row.put("phone_number", phoneNumber);
            row.put("language_id", rs.getObject("language_id"));
            row.put("whatsapp_connection_id", rs.getObject("whatsapp_connection_id"));
            row.put("scheme_id", rs.getObject("scheme_id"));
            consumer.accept(row);
            count[0]++;
        });
        return count[0];
    }

    /**
     * Streams OPERATOR users who have missed at least {@code minMissedDays} consecutive days
     * of uploads, plus any operators who have NEVER uploaded ({@code days_since_last_upload}
     * is {@code null}). Calls {@code consumer} once per row. Returns the total row count.
     *
     * <p>{@code days_since_last_upload} is the calendar-day gap between the operator's
     * most recent reading and today: {@code CURRENT_DATE - MAX(reading_date)}.
     * It is {@code null} when the operator has no readings at all.</p>
     *
     * <ul>
     *   <li>Last upload today → 0 (up to date, excluded by threshold)</li>
     *   <li>Last upload yesterday → 1 day</li>
     *   <li>Last upload 7 days ago → 7 days → level-2 escalation</li>
     *   <li>Never uploaded → NULL → always escalated (level-2)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public int streamUsersWithMissedDays(String schema, int minMissedDays, LocalDate referenceDate,
                                         Consumer<Map<String, Object>> consumer) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT user_id, name, phone_number, language_id, whatsapp_connection_id,
                       scheme_id, scheme_name, last_reading_date, days_since_last_upload,
                       last_confirmed_reading
                FROM (
                    SELECT
                      u.id AS user_id,
                      u.title AS name,
                      u.phone_number,
                      u.language_id,
                      u.whatsapp_connection_id,
                      usm.scheme_id,
                      sm.state_scheme_id AS scheme_name,
                      MAX(fr.reading_date) AS last_reading_date,
                      CASE
                        WHEN MAX(fr.reading_date) IS NULL THEN NULL
                        ELSE CAST(? AS DATE) - MAX(fr.reading_date)
                      END AS days_since_last_upload,
                      (SELECT fr2.confirmed_reading
                         FROM %s.flow_reading_table fr2
                        WHERE fr2.scheme_id = usm.scheme_id
                          AND fr2.created_by = u.id
                          AND fr2.confirmed_reading IS NOT NULL
                        ORDER BY fr2.reading_date DESC
                        LIMIT 1) AS last_confirmed_reading
                    FROM %s.user_scheme_mapping_table usm
                    JOIN %s.user_table u ON u.id = usm.user_id
                    JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                    LEFT JOIN %s.flow_reading_table fr
                        ON fr.scheme_id = usm.scheme_id AND fr.created_by = u.id
                    LEFT JOIN %s.scheme_master_table sm ON sm.id = usm.scheme_id
                    WHERE usm.status = 1
                      AND u.status = 1
                      AND UPPER(ut.c_name) = 'PUMP_OPERATOR'
                    GROUP BY u.id, u.title, u.phone_number, u.language_id, u.whatsapp_connection_id,
                             usm.scheme_id, sm.state_scheme_id
                ) sub
                WHERE days_since_last_upload IS NULL
                   OR days_since_last_upload >= ?
                """, schema, schema, schema, schema, schema);
        log.debug("streamUsersWithMissedDays – schema={}, minMissedDays={}", schema, minMissedDays);
        int[] count = {0};
        jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setObject(1, referenceDate);
            ps.setInt(2, minMissedDays);
            ps.setFetchSize(500);
            return ps;
        }, rs -> {
            Object userId = rs.getObject("user_id");
            String rawName = rs.getString("name");
            String rawPhone = rs.getString("phone_number");
            String name = decryptPii(rawName, "name", userId, schema);
            String phoneNumber = decryptPii(rawPhone, "phone_number", userId, schema);
            if ((rawName != null && name == null) || (rawPhone != null && phoneNumber == null)) return;
            Map<String, Object> row = new HashMap<>(10);
            row.put("user_id", userId);
            row.put("name", name);
            row.put("phone_number", phoneNumber);
            row.put("language_id", rs.getObject("language_id"));
            row.put("whatsapp_connection_id", rs.getObject("whatsapp_connection_id"));
            row.put("scheme_id", rs.getObject("scheme_id"));
            row.put("scheme_name", rs.getString("scheme_name"));
            row.put("last_reading_date", rs.getObject("last_reading_date"));
            row.put("days_since_last_upload", rs.getObject("days_since_last_upload"));
            row.put("last_confirmed_reading", rs.getObject("last_confirmed_reading"));
            consumer.accept(row);
            count[0]++;
        });
        return count[0];
    }

    /**
     * Returns the name and phone number of the officer (by {@code userTypeName})
     * mapped to a given scheme, or {@code null} if none found.
     */
    public Map<String, Object> findOfficerByUserType(String schema, Object schemeId, String userTypeName) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT u.id as user_id, u.title as name, u.phone_number, u.language_id,
                       u.whatsapp_connection_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                WHERE usm.scheme_id = ? AND UPPER(ut.c_name) = UPPER(?) AND usm.status = 1 AND u.status = 1
                ORDER BY u.id
                LIMIT 1
                """, schema, schema);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, schemeId, userTypeName);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = new HashMap<>(rows.get(0));
        try {
            row.put("name", pii.safeDecrypt((String) row.get("name")));
        } catch (RuntimeException e) {
            log.warn("PII decryption failed for 'name', user_id={} schema='{}'", row.get("user_id"), schema);
            row.put("name", null);
        }
        try {
            row.put("phone_number", pii.safeDecrypt((String) row.get("phone_number")));
        } catch (RuntimeException e) {
            log.warn("PII decryption failed for 'phone_number', user_id={} schema='{}'", row.get("user_id"), schema);
            row.put("phone_number", null);
        }
        return row;
    }

    /**
     * Loads one officer row per scheme for the given {@code userTypeName} across all schemes
     * in the tenant, returning a map keyed by {@code scheme_id}.
     *
     * <p>When multiple officers of the same type exist for a scheme the first row encountered
     * is kept (consistent with the single-row behaviour of {@link #findOfficerByUserType}).</p>
     */
    public Map<Object, Map<String, Object>> findAllOfficersByUserType(String schema, String userTypeName) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT DISTINCT ON (usm.scheme_id)
                       usm.scheme_id, u.id as user_id, u.title as name, u.phone_number,
                       u.language_id, u.whatsapp_connection_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                WHERE UPPER(ut.c_name) = UPPER(?) AND usm.status = 1 AND u.status = 1
                ORDER BY usm.scheme_id, u.id
                """, schema, schema);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userTypeName);
        Map<Object, Map<String, Object>> result = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> decrypted = new HashMap<>(row);
                decrypted.put("name", pii.safeDecrypt((String) row.get("name")));
                decrypted.put("phone_number", pii.safeDecrypt((String) row.get("phone_number")));
                result.putIfAbsent(decrypted.get("scheme_id"), decrypted);
            } catch (RuntimeException e) {
                log.warn("Skipping officer row for user_id={} due to PII decryption failure", row.get("user_id"));
            }
        }
        return result;
    }

    /**
     * Persists the Glific contact ID for the given user.
     * Called by the Kafka consumer when a {@code WHATSAPP_CONTACT_REGISTERED} event arrives.
     */
    public int updateWhatsAppConnectionId(String schema, long userId, long contactId) {
        validateSchemaName(schema);
        String sql = String.format("UPDATE %s.user_table SET whatsapp_connection_id = ? WHERE id = ?", schema);
        return jdbcTemplate.update(sql, contactId, userId);
    }

    /**
     * Decrypts a PII field for use inside streaming row callbacks.
     *
     * <p>Mirrors the legacy-plaintext fallback in {@link PiiEncryptionService#safeDecrypt}
     * but additionally catches actual decryption failures (e.g. tampered ciphertext, wrong key)
     * and returns {@code null} instead of propagating, so a single corrupted row does not abort
     * the entire stream batch.
     *
     * @return decrypted value; the raw value for legacy plaintext; or {@code null} on decryption
     *         failure (the caller must skip the row)
     */
    private String decryptPii(String encoded, String fieldName, Object userId, String schema) {
        if (encoded == null) return null;
        // Minimum AES-256-GCM payload: 12-byte IV + 16-byte GCM auth tag = 28 bytes.
        // Values shorter than this (or not valid Base64) are legacy plaintext rows.
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
            if (decoded.length < 28) {
                return encoded;
            }
        } catch (IllegalArgumentException e) {
            return encoded;
        }
        try {
            return pii.decrypt(encoded);
        } catch (Exception e) {
            log.error("PII decryption failed – field='{}' user_id={} schema='{}', skipping row",
                    fieldName, userId, schema, e);
            return null;
        }
    }

    private void validateSchemaName(String schema) {
        if (schema == null || !schema.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schema);
        }
    }
}
