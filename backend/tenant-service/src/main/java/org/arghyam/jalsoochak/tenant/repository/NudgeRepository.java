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
 * {@code ^tenant_[a-z0-9][a-z0-9_]{0,29}$} ensures only valid tenant schema names
 * reach the query, preventing access to shared/internal schemas. All runtime data values
 * (dates, IDs, counts) are bound as {@code ?} parameters and never concatenated into SQL.</p>
 *
 * <p><b>Soft deletes:</b> every query here filters {@code deleted_at IS NULL} on both
 * {@code user_table} and {@code user_scheme_mapping_table}, in addition to {@code status = 1}.
 * The status predicate alone is not sufficient: a scheme mapping is soft-deleted by stamping
 * {@code deleted_at} <em>without</em> clearing {@code status}
 * (see {@code UserUploadRepository#markUserSchemeMappingsDeleted}), so a removed mapping still
 * reads as active. Without the {@code deleted_at} guard these queries keep nudging, escalating
 * and reporting on people for schemes they no longer hold.</p>
 */
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
    @SuppressWarnings("java:S2077")
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
                  AND usm.deleted_at IS NULL
                  AND u.status = 1
                  AND u.deleted_at IS NULL
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
    @SuppressWarnings("java:S2077")
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
                      AND usm.deleted_at IS NULL
                      AND u.status = 1
                      AND u.deleted_at IS NULL
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
    @SuppressWarnings("java:S2077")
    public Map<String, Object> findOfficerByUserType(String schema, Object schemeId, String userTypeName) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT u.id as user_id, u.title as name, u.phone_number, u.language_id,
                       u.whatsapp_connection_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                WHERE usm.scheme_id = ?
                  AND UPPER(ut.c_name) = UPPER(?)
                  AND usm.status = 1
                  AND usm.deleted_at IS NULL
                  AND u.status = 1
                  AND u.deleted_at IS NULL
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
    @SuppressWarnings("java:S2077")
    public Map<Object, Map<String, Object>> findAllOfficersByUserType(String schema, String userTypeName) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT DISTINCT ON (usm.scheme_id)
                       usm.scheme_id, u.id as user_id, u.title as name, u.phone_number,
                       u.language_id, u.whatsapp_connection_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                WHERE UPPER(ut.c_name) = UPPER(?)
                  AND usm.status = 1
                  AND usm.deleted_at IS NULL
                  AND u.status = 1
                  AND u.deleted_at IS NULL
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
     * Returns the distinct user ids of officers of the given type ({@code SECTION_OFFICER},
     * {@code SUB_DIVISIONAL_OFFICER}, …) who oversee at least one active scheme in the tenant.
     *
     * <p>Used by the Daily Water Service Situation Report scheduler to decide which officers to
     * request a report for. Returns ids only — no PII is read here (message-service resolves the
     * officer's contact when it delivers the report).</p>
     *
     * <p>Soft-deleted users and mappings are excluded — see the class Javadoc for why
     * {@code status = 1} alone does not cover this.</p>
     */
    @SuppressWarnings("java:S2077")
    public List<Long> findDistinctOfficerUserIdsByUserType(String schema, String userTypeName) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT DISTINCT u.id AS user_id
                FROM %s.user_scheme_mapping_table usm
                JOIN %s.user_table u ON u.id = usm.user_id
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                WHERE UPPER(ut.c_name) = UPPER(?)
                  AND usm.status = 1
                  AND usm.deleted_at IS NULL
                  AND u.status = 1
                  AND u.deleted_at IS NULL
                """, schema, schema);
        return jdbcTemplate.queryForList(sql, Long.class, userTypeName);
    }

    /**
     * Returns the distinct user ids of {@code SECTION_OFFICER}s who share at least one active scheme
     * with the given Sub-Divisional Officer — i.e. the Section Officers "under" that SDO. There is no
     * explicit SDO→SO hierarchy in the schema, so the relationship is derived through the schemes both
     * are mapped to in {@code user_scheme_mapping_table}.
     *
     * <p>Used by the Daily Water Service Situation Report scheduler to build the SDO report's
     * per-officer Summary breakdown. Returns ids only — no PII (message-service resolves each
     * officer's name/mobile at render time; analytics computes their KPIs).</p>
     *
     * <p>Soft-deleted rows are excluded on all four: the SDO's own mapping and user row, and the Section
     * Officer's mapping and user row. The SDO side matters as much as the SO side — a scheme the SDO no
     * longer oversees must not pull that scheme's Section Officers into the breakdown, and a deleted SDO
     * has no breakdown to build. The SDO's user row is checked here rather than left to the caller so
     * the method is safe for any caller, not only the scheduler that happens to resolve live officers
     * first.</p>
     */
    @SuppressWarnings("java:S2077")
    public List<Long> findSubordinateSectionOfficerIds(String schema, long sdoUserId) {
        validateSchemaName(schema);
        String sql = String.format("""
                SELECT DISTINCT so_u.id AS user_id
                FROM %1$s.user_scheme_mapping_table sdo_usm
                JOIN %1$s.user_table sdo_u ON sdo_u.id = sdo_usm.user_id
                JOIN %1$s.user_scheme_mapping_table so_usm
                    ON so_usm.scheme_id = sdo_usm.scheme_id
                JOIN %1$s.user_table so_u ON so_u.id = so_usm.user_id
                JOIN common_schema.user_type_master_table so_ut ON so_ut.id = so_u.user_type
                WHERE sdo_usm.user_id = ?
                  AND sdo_usm.status = 1
                  AND sdo_usm.deleted_at IS NULL
                  AND sdo_u.deleted_at IS NULL
                  AND so_usm.status = 1
                  AND so_usm.deleted_at IS NULL
                  AND so_u.status = 1
                  AND so_u.deleted_at IS NULL
                  AND UPPER(so_ut.c_name) = 'SECTION_OFFICER'
                """, schema);
        return jdbcTemplate.queryForList(sql, Long.class, sdoUserId);
    }

    /**
     * Persists the Glific contact ID for the given user.
     * Called by the Kafka consumer when a {@code WHATSAPP_CONTACT_REGISTERED} event arrives.
     */
    @SuppressWarnings("java:S2077")
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
        if (schema == null || !schema.matches("^tenant_[a-z0-9][a-z0-9_]{0,29}$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schema);
        }
    }
}
