package org.arghyam.jalsoochak.tenant.repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.tenant.dto.internal.TenantProviderSecretDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantSecretKeyDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MESSAGING-PROVIDER-SECRETS: repository for {@code common_schema.tenant_secret_key} and
 * {@code common_schema.tenant_provider_secret}.
 *
 * <p>{@link JdbcTemplate} with schema-qualified SQL, matching {@link TenantCommonRepository}:
 * the connection's {@code search_path} is not relied on anywhere in this service.
 *
 * <p>This class moves ciphertext only. It never sees a plaintext secret and never sees
 * key material, so nothing it logs can leak one. Every read of
 * {@code tenant_provider_secret} filters {@code deleted_at IS NULL}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class TenantProviderSecretRepository {

    /** Status of the one key version a tenant's new writes use. */
    public static final String KEY_STATUS_ACTIVE = "ACTIVE";

    /** Status of a superseded key version, kept for audit. */
    public static final String KEY_STATUS_RETIRED = "RETIRED";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TenantSecretKeyDTO> SECRET_KEY_ROW_MAPPER = (rs, rowNum) -> TenantSecretKeyDTO
            .builder()
            .id(rs.getInt("id"))
            .tenantId(rs.getInt("tenant_id"))
            .keyVersion(rs.getInt("key_version"))
            .wrappedKey(rs.getString("wrapped_key"))
            .masterKeyId(rs.getString("master_key_id"))
            .status(rs.getString("status"))
            .createdAt(rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime()
                    : null)
            .createdBy((Integer) rs.getObject("created_by"))
            .updatedAt(rs.getTimestamp("updated_at") != null
                    ? rs.getTimestamp("updated_at").toLocalDateTime()
                    : null)
            .updatedBy((Integer) rs.getObject("updated_by"))
            .build();

    private static final RowMapper<TenantProviderSecretDTO> SECRET_ROW_MAPPER = (rs, rowNum) -> TenantProviderSecretDTO
            .builder()
            .id(rs.getInt("id"))
            .uuid(rs.getString("uuid"))
            .tenantId(rs.getInt("tenant_id"))
            .channel(MessagingChannel.valueOf(rs.getString("channel")))
            .secretName(rs.getString("secret_name"))
            .ciphertext(rs.getString("ciphertext"))
            .keyVersion(rs.getInt("key_version"))
            .createdAt(rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toLocalDateTime()
                    : null)
            .createdBy((Integer) rs.getObject("created_by"))
            .updatedAt(rs.getTimestamp("updated_at") != null
                    ? rs.getTimestamp("updated_at").toLocalDateTime()
                    : null)
            .updatedBy((Integer) rs.getObject("updated_by"))
            .build();

    // ── tenant_secret_key ───────────────────────────────────────────────────────

    /** The tenant's usable key version, or empty if no secret has ever been written for it. */
    public Optional<TenantSecretKeyDTO> findActiveKey(Integer tenantId) {
        String sql = """
                SELECT * FROM common_schema.tenant_secret_key
                WHERE tenant_id = ? AND status = ?
                """;
        return jdbcTemplate.query(sql, SECRET_KEY_ROW_MAPPER, tenantId, KEY_STATUS_ACTIVE)
                .stream().findFirst();
    }

    /** Any key version of a tenant, active or retired — used to decrypt an older ciphertext. */
    public Optional<TenantSecretKeyDTO> findKey(Integer tenantId, Integer keyVersion) {
        String sql = """
                SELECT * FROM common_schema.tenant_secret_key
                WHERE tenant_id = ? AND key_version = ?
                """;
        return jdbcTemplate.query(sql, SECRET_KEY_ROW_MAPPER, tenantId, keyVersion)
                .stream().findFirst();
    }

    /**
     * Every key row in the platform, ordered for a stable rewrap. Retired versions are
     * included: a KEK rotation is only finished when no row still names the outgoing key,
     * so rows that will never encrypt again still have to be re-wrapped.
     */
    public List<TenantSecretKeyDTO> findAllKeys() {
        String sql = """
                SELECT * FROM common_schema.tenant_secret_key
                ORDER BY tenant_id, key_version
                """;
        return jdbcTemplate.query(sql, SECRET_KEY_ROW_MAPPER);
    }

    /** The highest key version ever issued to a tenant, retired ones included, or 0 if none. */
    public int findMaxKeyVersion(Integer tenantId) {
        String sql = """
                SELECT COALESCE(MAX(key_version), 0) FROM common_schema.tenant_secret_key
                WHERE tenant_id = ?
                """;
        Integer max = jdbcTemplate.queryForObject(sql, Integer.class, tenantId);
        return max == null ? 0 : max;
    }

    /**
     * Inserts a new ACTIVE key version.
     *
     * <p>{@code uq_tenant_secret_key_active} permits one ACTIVE row per tenant, so a
     * rotation must call {@link #retireKey} on the outgoing version <em>before</em> this.
     */
    public TenantSecretKeyDTO insertActiveKey(Integer tenantId, int keyVersion, String wrappedKey,
            String masterKeyId, Integer currentUserId) {
        String sql = """
                INSERT INTO common_schema.tenant_secret_key
                    (tenant_id, key_version, wrapped_key, master_key_id, status, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """;
        List<TenantSecretKeyDTO> rows = jdbcTemplate.query(sql, SECRET_KEY_ROW_MAPPER,
                tenantId, keyVersion, wrappedKey, masterKeyId, KEY_STATUS_ACTIVE, currentUserId, currentUserId);
        return rows.stream().findFirst().orElseThrow(() -> new IllegalStateException(
                "Failed to insert secret key for tenant " + tenantId + " version " + keyVersion));
    }

    /** Replaces a key's wrapping without touching the data key itself. Used by the KEK rewrap. */
    public int updateWrappedKey(Integer id, String wrappedKey, String masterKeyId, Integer currentUserId) {
        String sql = """
                UPDATE common_schema.tenant_secret_key
                SET wrapped_key = ?, master_key_id = ?, updated_at = NOW(), updated_by = ?
                WHERE id = ?
                """;
        return jdbcTemplate.update(sql, wrappedKey, masterKeyId, currentUserId, id);
    }

    /** Marks a key version RETIRED, freeing the partial unique index for the next ACTIVE row. */
    public int retireKey(Integer id, Integer currentUserId) {
        String sql = """
                UPDATE common_schema.tenant_secret_key
                SET status = ?, updated_at = NOW(), updated_by = ?
                WHERE id = ? AND status = ?
                """;
        return jdbcTemplate.update(sql, KEY_STATUS_RETIRED, currentUserId, id, KEY_STATUS_ACTIVE);
    }

    // ── tenant_provider_secret ──────────────────────────────────────────────────

    /**
     * Writes one secret, creating or overwriting the row for
     * {@code (tenantId, channel, secretName)}.
     *
     * <p>{@code uq_tenant_provider_secret} is unconditional, so a previously soft-deleted
     * secret collides here and is revived by clearing {@code deleted_at} — rather than
     * being blocked, or leaving a dead row beside a live one.
     */
    public TenantProviderSecretDTO upsertSecret(Integer tenantId, MessagingChannel channel, String secretName,
            String ciphertext, int keyVersion, Integer currentUserId) {
        String sql = """
                INSERT INTO common_schema.tenant_provider_secret
                    (tenant_id, channel, secret_name, ciphertext, key_version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, channel, secret_name)
                DO UPDATE SET
                    ciphertext  = EXCLUDED.ciphertext,
                    key_version = EXCLUDED.key_version,
                    updated_at  = NOW(),
                    updated_by  = ?,
                    deleted_at  = NULL,
                    deleted_by  = NULL
                RETURNING *
                """;
        List<TenantProviderSecretDTO> rows = jdbcTemplate.query(sql, SECRET_ROW_MAPPER,
                tenantId, channel.name(), secretName, ciphertext, keyVersion,
                currentUserId, currentUserId, currentUserId);
        return rows.stream().findFirst().orElseThrow(() -> new IllegalStateException(
                "Failed to upsert secret [tenantId=" + tenantId + ", channel=" + channel
                        + ", secretName=" + secretName + "]"));
    }

    /** Live secrets for one channel of one tenant. */
    public List<TenantProviderSecretDTO> findByTenantAndChannel(Integer tenantId, MessagingChannel channel) {
        String sql = """
                SELECT * FROM common_schema.tenant_provider_secret
                WHERE tenant_id = ? AND channel = ? AND deleted_at IS NULL
                ORDER BY secret_name
                """;
        return jdbcTemplate.query(sql, SECRET_ROW_MAPPER, tenantId, channel.name());
    }

    /** Every live secret of one tenant, across channels. Used by a per-tenant key rotation. */
    public List<TenantProviderSecretDTO> findByTenant(Integer tenantId) {
        String sql = """
                SELECT * FROM common_schema.tenant_provider_secret
                WHERE tenant_id = ? AND deleted_at IS NULL
                ORDER BY channel, secret_name
                """;
        return jdbcTemplate.query(sql, SECRET_ROW_MAPPER, tenantId);
    }

    /**
     * Names of the live secrets on a channel — the only shape a read may take outside the
     * service layer's own re-encryption paths, and what {@code GET} reports as status.
     */
    public Set<String> findSecretNames(Integer tenantId, MessagingChannel channel) {
        String sql = """
                SELECT secret_name FROM common_schema.tenant_provider_secret
                WHERE tenant_id = ? AND channel = ? AND deleted_at IS NULL
                ORDER BY secret_name
                """;
        return new LinkedHashSet<>(jdbcTemplate.queryForList(sql, String.class, tenantId, channel.name()));
    }

    /** Re-points an existing secret at a new ciphertext and key version, for a key rotation. */
    public int updateCiphertext(Integer id, String ciphertext, int keyVersion, Integer currentUserId) {
        String sql = """
                UPDATE common_schema.tenant_provider_secret
                SET ciphertext = ?, key_version = ?, updated_at = NOW(), updated_by = ?
                WHERE id = ? AND deleted_at IS NULL
                """;
        return jdbcTemplate.update(sql, ciphertext, keyVersion, currentUserId, id);
    }

    /**
     * Soft-deletes every live secret on a channel and returns how many rows were affected.
     * Soft, not hard, to match {@code tenant_config_master_table}: a credential that
     * disappears from a tenant's configuration should still be traceable to who removed it.
     */
    public int softDeleteByTenantAndChannel(Integer tenantId, MessagingChannel channel, Integer currentUserId) {
        String sql = """
                UPDATE common_schema.tenant_provider_secret
                SET deleted_at = NOW(), deleted_by = ?, updated_at = NOW(), updated_by = ?
                WHERE tenant_id = ? AND channel = ? AND deleted_at IS NULL
                """;
        return jdbcTemplate.update(sql, currentUserId, currentUserId, tenantId, channel.name());
    }
}
