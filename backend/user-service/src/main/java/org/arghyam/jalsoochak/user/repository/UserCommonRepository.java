package org.arghyam.jalsoochak.user.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UserCommonRepository {

    private final JdbcTemplate jdbcTemplate;
    private final PiiEncryptionService pii;

    // --- Tenant lookups ---

    public boolean existsTenantByStateCode(String stateCode) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM common_schema.tenant_master_table
                    WHERE LOWER(state_code) = LOWER(?)
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, stateCode);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<Integer> findTenantIdByStateCode(String stateCode) {
        String sql = """
                SELECT id
                FROM common_schema.tenant_master_table
                WHERE LOWER(state_code) = LOWER(?)
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getInt("id"), stateCode);
        return rows.stream().findFirst();
    }

    public Optional<Integer> findTenantStatusByTenantId(Integer tenantId) {
        String sql = """
                SELECT status
                FROM common_schema.tenant_master_table
                WHERE id = ?
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getInt("status"), tenantId);
        return rows.stream().findFirst();
    }

    public Optional<String> findTenantStateCodeById(Integer tenantId) {
        String sql = """
                SELECT state_code
                FROM common_schema.tenant_master_table
                WHERE id = ?
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getString("state_code"), tenantId);
        return rows.stream().findFirst();
    }

    /**
     * Returns UUIDs of PENDING state-admin users whose invite token metadata contains
     * a {@code nameHash} matching the given HMAC-SHA256 value.
     * Scoped to {@code tenantId} when non-null, or all tenants when null.
     */
    public Set<String> findPendingAdminUuidsByNameHash(String nameHash, Integer tenantId) {
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT t.uuid
                FROM common_schema.tenant_admin_user_master_table t
                JOIN common_schema.admin_user_token_table tok ON tok.email = t.email
                WHERE t.status = ?
                  AND tok.token_type = 'INVITE'
                  AND tok.used_at IS NULL
                  AND tok.deleted_at IS NULL
                  AND tok.metadata->>'nameHash' = ?
                """);
        args.add(AdminUserStatus.PENDING.code);
        args.add(nameHash);
        if (tenantId != null) {
            sql.append(" AND t.tenant_id = ?");
            args.add(tenantId);
        } else {
            sql.append(" AND t.tenant_id != 0");
        }
        return new HashSet<>(jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray()));
    }

    public List<String> findAllTenantStateCodes() {
        return jdbcTemplate.queryForList(
                "SELECT state_code FROM common_schema.tenant_master_table ORDER BY state_code",
                String.class);
    }

    public Optional<String> findTenantTitleByStateCode(String stateCode) {
        String sql = """
                SELECT title
                FROM common_schema.tenant_master_table
                WHERE LOWER(state_code) = LOWER(?)
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getString("title"), stateCode);
        return rows.stream().findFirst();
    }

    // --- User type lookups ---

    public Optional<Integer> findUserTypeIdByName(String cName) {
        String sql = """
                SELECT id
                FROM common_schema.user_type_master_table
                WHERE LOWER(c_name) = LOWER(?)
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getInt("id"), cName);
        return rows.stream().findFirst();
    }

    public Optional<String> findUserTypeNameById(Integer id) {
        String sql = """
                SELECT c_name
                FROM common_schema.user_type_master_table
                WHERE id = ?
                LIMIT 1
                """;
        List<String> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getString("c_name"), id);
        return rows.stream().findFirst();
    }

    // --- Admin user CRUD ---

    public Long createAdminUser(String uuid, String email, String phoneNumber,
                                Integer tenantId, Integer adminLevelId, Integer createdById) {
        String sql = """
                INSERT INTO common_schema.tenant_admin_user_master_table
                    (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'KEYCLOAK_MANAGED', 1, ?, NOW(), NOW())
                RETURNING id
                """;
        Number id = jdbcTemplate.queryForObject(sql, Number.class,
                uuid, email, pii.encrypt(phoneNumber), pii.hmac(phoneNumber),
                tenantId, adminLevelId, createdById);
        return id != null ? id.longValue() : null;
    }

    /**
     * Create a PENDING admin user at invite time (before Keycloak account exists).
     * status=2 (PENDING). UUID is a placeholder; replaced at activation.
     */
    public Long createAdminUserPending(String email, String phoneNumber, Integer tenantId, Integer adminLevelId, Integer createdById) {
        String sql = """
                INSERT INTO common_schema.tenant_admin_user_master_table
                    (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status, created_by, created_at, updated_at)
                VALUES (gen_random_uuid()::TEXT, ?, ?, ?, ?, ?, 'KEYCLOAK_MANAGED', 2, ?, NOW(), NOW())
                RETURNING id
                """;
        Number id = jdbcTemplate.queryForObject(sql, Number.class,
                email, pii.encrypt(phoneNumber), pii.hmac(phoneNumber),
                tenantId, adminLevelId, createdById);
        return id != null ? id.longValue() : null;
    }

    /**
     * Complete activation of a PENDING admin user after Keycloak account creation.
     * Sets the real Keycloak UUID, phone number, and status = 1 (active).
     */
    public void activatePendingAdminUser(Long id, String keycloakUuid, String phoneNumber) {
        int updated = jdbcTemplate.update("""
                UPDATE common_schema.tenant_admin_user_master_table
                SET uuid = ?, phone_number = ?, phone_number_hash = ?, status = 1, updated_at = NOW()
                WHERE id = ? AND status = 2 AND deleted_at IS NULL
                """, keycloakUuid, pii.encrypt(phoneNumber), pii.hmac(phoneNumber), id);
        if (updated != 1) {
            throw new IllegalStateException("Expected to activate exactly one pending user with id=" + id + " but updated " + updated + " rows");
        }
    }

    public Optional<AdminUserRow> findAdminUserByUuid(String uuid) {
        String sql = """
                SELECT id, uuid, email, phone_number, tenant_id, admin_level, status, created_by, created_at
                FROM common_schema.tenant_admin_user_master_table
                WHERE uuid = ?
                LIMIT 1
                """;
        List<AdminUserRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapAdminUserRow(rs), uuid);
        return rows.stream().findFirst();
    }

    public Optional<AdminUserRow> findAdminUserByEmail(String email) {
        String sql = """
                SELECT id, uuid, email, phone_number, tenant_id, admin_level, status, created_by, created_at
                FROM common_schema.tenant_admin_user_master_table
                WHERE LOWER(email) = LOWER(?)
                LIMIT 1
                """;
        List<AdminUserRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapAdminUserRow(rs), email);
        return rows.stream().findFirst();
    }

    public Optional<AdminUserRow> findAdminUserById(Long id) {
        String sql = """
                SELECT id, uuid, email, phone_number, tenant_id, admin_level, status, created_by, created_at
                FROM common_schema.tenant_admin_user_master_table
                WHERE id = ?
                LIMIT 1
                """;
        List<AdminUserRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapAdminUserRow(rs), id);
        return rows.stream().findFirst();
    }

    public boolean existsAdminUserByEmail(String email) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM common_schema.tenant_admin_user_master_table
                    WHERE LOWER(email) = LOWER(?)
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, email);
        return Boolean.TRUE.equals(exists);
    }

    /** True only if a non-PENDING (active or deactivated) user exists with this email. */
    public boolean existsActiveAdminUserByEmail(String email) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM common_schema.tenant_admin_user_master_table
                    WHERE LOWER(email) = LOWER(?) AND status != 2
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, email);
        return Boolean.TRUE.equals(exists);
    }

    public void updateAdminUserProfile(Long id, String phoneNumber, Long updatedById) {
        String sql = """
                UPDATE common_schema.tenant_admin_user_master_table
                SET phone_number = ?, phone_number_hash = ?, updated_by = ?, updated_at = NOW()
                WHERE id = ?
                """;
        jdbcTemplate.update(sql, pii.encrypt(phoneNumber), pii.hmac(phoneNumber), updatedById, id);
    }

    public void deactivateAdminUser(Long id, Long actorId) {
        String sql = """
                UPDATE common_schema.tenant_admin_user_master_table
                SET status = 0, deleted_at = NOW(), deleted_by = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql, actorId, id);
    }

    public void activateAdminUser(Long id, Long actorId) {
        String sql = """
                UPDATE common_schema.tenant_admin_user_master_table
                SET status = 1, deleted_at = NULL, deleted_by = NULL, updated_by = ?, updated_at = NOW()
                WHERE id = ?
                """;
        jdbcTemplate.update(sql, actorId, id);
    }

    // --- Counting for minimum-active guards ---

    public int countActiveSuperUsers() {
        String sql = """
                SELECT COUNT(*)
                FROM common_schema.tenant_admin_user_master_table
                WHERE tenant_id = 0 AND status = 1 AND deleted_at IS NULL
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    public int countActiveStateAdminsForTenant(Integer tenantId) {
        String sql = """
                SELECT COUNT(*)
                FROM common_schema.tenant_admin_user_master_table
                WHERE tenant_id = ? AND status = 1 AND deleted_at IS NULL
                """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tenantId);
        return count != null ? count : 0;
    }

    // --- Listing (paginated) ---

    public List<AdminUserRow> listSuperUsers(AdminUserStatus status, long offset, int limit) {
        if (limit <= 0) {
            throw new BadRequestException("limit must be greater than 0");
        }
        if (offset < 0) {
            throw new BadRequestException("offset must be non-negative");
        }
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, uuid, email, phone_number, tenant_id, admin_level, status, created_by, created_at
                FROM common_schema.tenant_admin_user_master_table
                WHERE tenant_id = 0
                """);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.code);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), (rs, n) -> mapAdminUserRow(rs), args.toArray());
    }

    public long countSuperUsers(AdminUserStatus status) {
        if (status == null) {
            String sql = """
                    SELECT COUNT(*)
                    FROM common_schema.tenant_admin_user_master_table
                    WHERE tenant_id = 0
                    """;
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            return count != null ? count : 0L;
        } else {
            String sql = """
                    SELECT COUNT(*)
                    FROM common_schema.tenant_admin_user_master_table
                    WHERE tenant_id = 0 AND status = ?
                    """;
            Long count = jdbcTemplate.queryForObject(sql, Long.class, status.code);
            return count != null ? count : 0L;
        }
    }

    public List<AdminUserRow> listStateAdminsByTenant(Integer tenantId, AdminUserStatus status,
            Set<String> nameFilterUuids, long offset, int limit) {
        if (limit <= 0) {
            throw new BadRequestException("limit must be greater than 0");
        }
        if (offset < 0) {
            throw new BadRequestException("offset must be non-negative");
        }
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, uuid, email, phone_number, tenant_id, admin_level, status, created_by, created_at
                FROM common_schema.tenant_admin_user_master_table
                WHERE
                """);
        if (tenantId == null) {
            sql.append(" tenant_id != 0");
        } else {
            sql.append(" tenant_id = ?");
            args.add(tenantId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.code);
        }
        if (nameFilterUuids != null && !nameFilterUuids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(nameFilterUuids.size(), "?"));
            sql.append(" AND uuid IN (").append(placeholders).append(")");
            args.addAll(nameFilterUuids);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), (rs, n) -> mapAdminUserRow(rs), args.toArray());
    }

    public long countStateAdminsByTenant(Integer tenantId, AdminUserStatus status, Set<String> nameFilterUuids) {
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM common_schema.tenant_admin_user_master_table
                WHERE
                """);
        if (tenantId == null) {
            sql.append(" tenant_id != 0");
        } else {
            sql.append(" tenant_id = ?");
            args.add(tenantId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.code);
        }
        if (nameFilterUuids != null && !nameFilterUuids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(nameFilterUuids.size(), "?"));
            sql.append(" AND uuid IN (").append(placeholders).append(")");
            args.addAll(nameFilterUuids);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count != null ? count : 0L;
    }

    // --- Token methods ---

    /**
     * Insert a new token without touching any existing active tokens for (email, tokenType).
     * Old tokens remain valid until consumed or expired (invalidate-on-use, not on-issue).
     * When a token is eventually consumed, all sibling tokens are superseded via deleted_at.
     */
    @Transactional
    public void insertToken(String email, String tokenHash, String tokenType,
                            String metadataJson, Instant expiresAt, Integer createdBy) {
        jdbcTemplate.update("""
                INSERT INTO common_schema.admin_user_token_table
                    (email, token_hash, token_type, metadata, expires_at, created_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, email.toLowerCase(), tokenHash, tokenType, metadataJson,
                Timestamp.from(expiresAt), createdBy);
    }

    /**
     * Mark all other pending tokens for the same (email, tokenType) as deleted (superseded).
     * Called atomically after a token is consumed so sibling links stop working.
     */
    private void supersedeSiblingTokens(String email, String tokenType, String usedTokenHash) {
        jdbcTemplate.update("""
                UPDATE common_schema.admin_user_token_table
                SET deleted_at = NOW()
                WHERE LOWER(email) = LOWER(?)
                  AND token_type = ?
                  AND token_hash != ?
                  AND used_at IS NULL
                  AND deleted_at IS NULL
                """, email, tokenType, usedTokenHash);
    }

    /**
     * Find the most recent unconsumed, non-superseded INVITE token by email
     * (regardless of expiry — used to read name metadata for PENDING users).
     */
    public Optional<AdminUserTokenRow> findInviteTokenByEmail(String email) {
        String sql = """
                SELECT id, email, token_hash, token_type, metadata::TEXT, expires_at, used_at, deleted_at, created_at
                FROM common_schema.admin_user_token_table
                WHERE LOWER(email) = LOWER(?) AND token_type = 'INVITE' AND used_at IS NULL AND deleted_at IS NULL
                ORDER BY created_at DESC
                LIMIT 1
                """;
        List<AdminUserTokenRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapTokenRow(rs), email);
        return rows.stream().findFirst();
    }

    /** Find an active (not used, not deleted, not expired) INVITE token by email. */
    public Optional<AdminUserTokenRow> findActiveInviteTokenByEmail(String email) {
        String sql = """
                SELECT id, email, token_hash, token_type, metadata::TEXT, expires_at, used_at, deleted_at, created_at
                FROM common_schema.admin_user_token_table
                WHERE LOWER(email) = LOWER(?) AND token_type = 'INVITE' AND used_at IS NULL AND deleted_at IS NULL AND expires_at > NOW()
                LIMIT 1
                """;
        List<AdminUserTokenRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapTokenRow(rs), email);
        return rows.stream().findFirst();
    }

    /** Find an active (not used, not deleted, not expired) token by hash. */
    public Optional<AdminUserTokenRow> findActiveTokenByHash(String tokenHash) {
        String sql = """
                SELECT id, email, token_hash, token_type, metadata::TEXT, expires_at, used_at, deleted_at, created_at
                FROM common_schema.admin_user_token_table
                WHERE token_hash = ? AND used_at IS NULL AND deleted_at IS NULL AND expires_at > NOW()
                LIMIT 1
                """;
        List<AdminUserTokenRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapTokenRow(rs), tokenHash);
        return rows.stream().findFirst();
    }

    /**
     * Atomically validates and consumes an active, non-expired token in a single UPDATE,
     * then supersedes all other pending tokens for the same (email, token_type).
     * Returns the consumed row if successful; empty if the token is invalid, expired, or already used.
     */
    @Transactional
    public Optional<AdminUserTokenRow> consumeActiveToken(String tokenHash) {
        String sql = """
                UPDATE common_schema.admin_user_token_table
                SET used_at = NOW()
                WHERE token_hash = ? AND used_at IS NULL AND deleted_at IS NULL AND expires_at > NOW()
                RETURNING id, email, token_hash, token_type, metadata::TEXT, expires_at, used_at, deleted_at, created_at
                """;
        List<AdminUserTokenRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapTokenRow(rs), tokenHash);
        Optional<AdminUserTokenRow> result = rows.stream().findFirst();
        result.ifPresent(row -> supersedeSiblingTokens(row.email(), row.tokenType(), tokenHash));
        return result;
    }

    /**
     * Atomically validates and consumes an active, non-expired token only when its type matches,
     * then supersedes all other pending tokens for the same (email, token_type).
     * Returns the consumed row if successful; empty if invalid, expired, used, or type mismatch.
     */
    @Transactional
    public Optional<AdminUserTokenRow> consumeActiveTokenOfType(String tokenHash, String expectedType) {
        String sql = """
                UPDATE common_schema.admin_user_token_table
                SET used_at = NOW()
                WHERE token_hash = ? AND token_type = ? AND used_at IS NULL AND deleted_at IS NULL AND expires_at > NOW()
                RETURNING id, email, token_hash, token_type, metadata::TEXT, expires_at, used_at, deleted_at, created_at
                """;
        List<AdminUserTokenRow> rows = jdbcTemplate.query(sql, (rs, n) -> mapTokenRow(rs), tokenHash, expectedType);
        Optional<AdminUserTokenRow> result = rows.stream().findFirst();
        result.ifPresent(row -> supersedeSiblingTokens(row.email(), row.tokenType(), tokenHash));
        return result;
    }

    /** Admin revocation. */
    public void revokeToken(String tokenHash, Long revokedById) {
        jdbcTemplate.update("""
                UPDATE common_schema.admin_user_token_table
                SET deleted_at = NOW(), deleted_by = ?
                WHERE token_hash = ?
                """, revokedById, tokenHash);
    }

    // --- Row mappers ---

    private AdminUserRow mapAdminUserRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        String phoneNumber = pii.safeDecrypt(rs.getString("phone_number"));
        return new AdminUserRow(
                rs.getLong("id"),
                rs.getString("uuid"),
                rs.getString("email"),
                phoneNumber,
                rs.getInt("tenant_id"),
                rs.getInt("admin_level"),
                AdminUserStatus.fromCode(rs.getInt("status")),
                rs.getInt("created_by"),
                createdAtTs != null ? createdAtTs.toLocalDateTime() : null
        );
    }

    private AdminUserTokenRow mapTokenRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp expiresAtTs  = rs.getTimestamp("expires_at");
        Timestamp usedAtTs     = rs.getTimestamp("used_at");
        Timestamp deletedAtTs  = rs.getTimestamp("deleted_at");
        Timestamp createdAtTs  = rs.getTimestamp("created_at");
        return new AdminUserTokenRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("token_hash"),
                rs.getString("token_type"),
                rs.getString("metadata"),
                expiresAtTs  != null ? expiresAtTs.toInstant()  : null,
                usedAtTs     != null ? usedAtTs.toInstant()     : null,
                deletedAtTs  != null ? deletedAtTs.toInstant()  : null,
                createdAtTs  != null ? createdAtTs.toInstant()  : null
        );
    }
}
