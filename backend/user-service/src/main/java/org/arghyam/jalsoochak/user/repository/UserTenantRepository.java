package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import org.arghyam.jalsoochak.user.enums.TenantUserStatus;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;

/**
 * All SQL in this repository uses {@code String.format} solely to inject a schema name
 * (e.g. {@code tenant_mp}) that has been validated by {@link #validateSchemaName} against
 * the strict allowlist regex {@code ^[a-z_][a-z0-9_]*$}.  JDBC {@code PreparedStatement}
 * parameters cannot be used for SQL identifiers (schema/table names), so format-based
 * injection of the pre-validated identifier is the only viable approach.
 * All user-supplied data values are bound via {@code ?} parameters — never concatenated.
 */
@SuppressWarnings("java:S2077")
@Repository
@RequiredArgsConstructor
public class UserTenantRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService pii;

    private void validateSchemaName(String schemaName) {
        if (schemaName == null || !schemaName.matches("^[a-z_][a-z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }
    }

    public Optional<TenantUserRecord> findUserById(String schemaName, Long userId) {
        validateSchemaName(schemaName);

        String sql = String.format("""
        SELECT u.id,
               u.tenant_id,
               u.phone_number,
               u.email,
               u.user_type,
               u.title,
               u.uuid,
               u.status,
               u.whatsapp_connection_id,
               ut.c_name
        FROM %s.user_table u
        LEFT JOIN common_schema.user_type_master_table ut
               ON ut.id = u.user_type
        WHERE u.id = ?
        """, schemaName);

        List<TenantUserRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TenantUserRecord(
                        toLong(rs.getObject("id")),
                        toInteger(rs.getObject("tenant_id")),
                        pii.safeDecrypt(rs.getString("phone_number")),
                        rs.getString("email"),
                        toLong(rs.getObject("user_type")),
                        rs.getString("c_name"),
                        pii.safeDecrypt(rs.getString("title")),
                        rs.getString("uuid"),
                        toInteger(rs.getObject("status")),
                        toLong(rs.getObject("whatsapp_connection_id"))
                ), userId);

        return rows.stream().findFirst();
    }

    public Optional<TenantUserRecord> findUserByEmail(String schemaName, String email) {
        validateSchemaName(schemaName);

        String sql = String.format("""
        SELECT u.id,
               u.tenant_id,
               u.phone_number,
               u.email,
               u.user_type,
               u.title,
               u.uuid,
               u.status,
               u.whatsapp_connection_id,
               ut.c_name
        FROM %s.user_table u
        LEFT JOIN common_schema.user_type_master_table ut
               ON ut.id = u.user_type
        WHERE LOWER(u.email) = LOWER(?)
        """, schemaName);

        List<TenantUserRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TenantUserRecord(
                        toLong(rs.getObject("id")),
                        toInteger(rs.getObject("tenant_id")),
                        pii.safeDecrypt(rs.getString("phone_number")),
                        rs.getString("email"),
                        toLong(rs.getObject("user_type")),
                        rs.getString("c_name"),
                        pii.safeDecrypt(rs.getString("title")),
                        rs.getString("uuid"),
                        toInteger(rs.getObject("status")),
                        toLong(rs.getObject("whatsapp_connection_id"))
                ), email);

        return rows.stream().findFirst();
    }

    public Optional<TenantUserRecord> findUserByKeycloakUuid(String schemaName, String keycloakUuid) {
        validateSchemaName(schemaName);
        if (keycloakUuid == null || keycloakUuid.isBlank()) {
            return Optional.empty();
        }

        String sql = String.format("""
        SELECT u.id,
               u.tenant_id,
               u.phone_number,
               u.email,
               u.user_type,
               u.title,
               u.uuid,
               u.status,
               u.whatsapp_connection_id,
               ut.c_name
        FROM %s.user_table u
        LEFT JOIN common_schema.user_type_master_table ut
               ON ut.id = u.user_type
        WHERE u.uuid = ?
        """, schemaName);

        List<TenantUserRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TenantUserRecord(
                        toLong(rs.getObject("id")),
                        toInteger(rs.getObject("tenant_id")),
                        pii.safeDecrypt(rs.getString("phone_number")),
                        rs.getString("email"),
                        toLong(rs.getObject("user_type")),
                        rs.getString("c_name"),
                        pii.safeDecrypt(rs.getString("title")),
                        rs.getString("uuid"),
                        toInteger(rs.getObject("status")),
                        toLong(rs.getObject("whatsapp_connection_id"))
                ), keycloakUuid);

        return rows.stream().findFirst();
    }

    public Optional<TenantUserRecord> findUserByPhone(String schemaName, String phoneNumber) {
        validateSchemaName(schemaName);
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Optional.empty();
        }

        // Lookup via HMAC hash — the encrypted column cannot be searched directly
        String sql = String.format("""
        SELECT u.id,
               u.tenant_id,
               u.phone_number,
               u.email,
               u.user_type,
               u.title,
               u.uuid,
               u.status,
               u.whatsapp_connection_id,
               ut.c_name
        FROM %s.user_table u
        LEFT JOIN common_schema.user_type_master_table ut
               ON ut.id = u.user_type
        WHERE u.phone_number_hash = ?
        """, schemaName);

        List<TenantUserRecord> rows = jdbcTemplate.query(sql, (rs, n) ->
                new TenantUserRecord(
                        toLong(rs.getObject("id")),
                        toInteger(rs.getObject("tenant_id")),
                        pii.safeDecrypt(rs.getString("phone_number")),
                        rs.getString("email"),
                        toLong(rs.getObject("user_type")),
                        rs.getString("c_name"),
                        pii.safeDecrypt(rs.getString("title")),
                        rs.getString("uuid"),
                        toInteger(rs.getObject("status")),
                        toLong(rs.getObject("whatsapp_connection_id"))
                ), pii.hmac(phoneNumber.trim()));

        return rows.stream().findFirst();
    }

    public Long createUser(String schemaName,
                           String uuid,
                           Integer tenantId,
                           String title,
                           String email,
                           Integer userTypeId,
                           String phoneNumber,
                           String password,
                           Long createdBy) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                INSERT INTO %s.user_table
                (
                    uuid,
                    tenant_id,
                    title,
                    title_hash,
                    email,
                    user_type,
                    phone_number,
                    phone_number_hash,
                    password,
                    status,
                    email_verification_status,
                    phone_verification_status,
                    created_by,
                    created_at,
                    updated_by,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, true, ?, NOW(), ?, NOW())
                RETURNING id
                """, schemaName);

        Number insertedId = jdbcTemplate.queryForObject(
                sql,
                Number.class,
                uuid,
                tenantId,
                pii.encrypt(title),
                pii.hmac(title == null ? "" : title.trim().toLowerCase(Locale.ROOT)),
                email,
                userTypeId,
                pii.encrypt(phoneNumber),
                pii.hmac(phoneNumber),
                password,
                TenantUserStatus.ACTIVE.code,
                createdBy,
                createdBy
        );
        return insertedId != null ? insertedId.longValue() : null;
    }

    public void updateUserProfile(String schemaName, Long id, String title, String phoneNumber) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                UPDATE %s.user_table
                SET title = ?, title_hash = ?, phone_number = ?, phone_number_hash = ?, updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql,
                pii.encrypt(title), pii.hmac(title == null ? "" : title.trim().toLowerCase(Locale.ROOT)),
                pii.encrypt(phoneNumber), pii.hmac(phoneNumber),
                id);
    }

    public int updateUserRole(String schemaName, Long userId, Long newUserTypeId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                UPDATE %s.user_table
                SET user_type = ?, updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        return jdbcTemplate.update(sql, newUserTypeId, userId);
    }

    public void updateUserLanguageId(String schemaName, Long userId, Integer languageId) {
        validateSchemaName(schemaName);
        if (userId == null) {
            return;
        }
        String sql = String.format("""
                UPDATE %s.user_table
                SET language_id = ?, updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql, languageId, userId);
    }

    /**
     * Streams phone numbers for users with the given roles and optional onboarding time window.
     * The RowCallbackHandler is invoked for each row to avoid loading everything into memory.
     */
    public void streamPhonesByRolesAndOnboardingWindow(
            String schemaName,
            List<String> roles,
            java.time.Instant onboardedAfter,
            java.time.Instant onboardedBefore,
            java.util.function.Consumer<String> phoneConsumer
    ) {
        validateSchemaName(schemaName);

        List<String> cleaned = roles == null ? List.of() : roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();

        if (cleaned.isEmpty()) {
            return;
        }

        String rolePlaceholders = String.join(", ", cleaned.stream().map(r -> "?").toList());
        StringBuilder sql = new StringBuilder(String.format("""
                SELECT u.phone_number
                FROM %s.user_table u
                LEFT JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type
                WHERE u.deleted_at IS NULL
                  AND lower(ut.c_name) IN (%s)
                """, schemaName, rolePlaceholders));

        List<Object> args = new java.util.ArrayList<>(cleaned);
        if (onboardedAfter != null) {
            sql.append(" AND u.created_at >= ?");
            args.add(java.sql.Timestamp.from(onboardedAfter));
        }
        if (onboardedBefore != null) {
            sql.append(" AND u.created_at < ?");
            args.add(java.sql.Timestamp.from(onboardedBefore));
        }

        jdbcTemplate.query(sql.toString(), rs -> {
            String encrypted = rs.getString("phone_number");
            if (encrypted == null || encrypted.isBlank()) {
                return;
            }
            String phone = pii.safeDecrypt(encrypted);
            if (phone == null || phone.isBlank()) {
                return;
            }
            phoneConsumer.accept(phone);
        }, args.toArray());
    }

    /**
     * Sets the Keycloak UUID and stores an AES-encrypted managed password for a staff user.
     * Called by {@code StaffKeycloakService} on first successful OTP login (lazy provisioning).
     */
    public void updateKeycloakUuidAndPassword(String schemaName, Long userId,
                                              String keycloakUuid, String encryptedPassword) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                UPDATE %s.user_table
                SET uuid = ?, password = ?, updated_at = NOW()
                WHERE id = ?
                """, schemaName);
        jdbcTemplate.update(sql, keycloakUuid, encryptedPassword, userId);
    }

    /**
     * Returns UUIDs of users in the given tenant schema whose {@code title_hash} matches
     * the provided HMAC-SHA256 hex value. Used for exact case-insensitive name search.
     */
    public List<String> findUuidsByTitleHash(String schemaName, String titleHash) {
        validateSchemaName(schemaName);
        String sql = String.format("SELECT uuid FROM %s.user_table WHERE title_hash = ?", schemaName);
        return jdbcTemplate.queryForList(sql, String.class, titleHash);
    }

    /**
     * Returns the raw {@code password} column value for a given user.
     * Used by {@code StaffKeycloakService} to determine whether a managed password
     * has already been set (non-placeholder value → decrypt with {@code PasswordCipher}).
     */
    public Optional<String> findPasswordByUserId(String schemaName, Long userId) {
        validateSchemaName(schemaName);
        String sql = String.format("""
                SELECT password
                FROM %s.user_table
                WHERE id = ?
                LIMIT 1
                """, schemaName);
        List<String> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getString("password"), userId);
        return rows.stream().findFirst();
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
}
