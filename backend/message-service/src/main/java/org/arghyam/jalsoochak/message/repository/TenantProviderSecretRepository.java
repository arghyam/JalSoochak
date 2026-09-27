package org.arghyam.jalsoochak.message.repository;

import java.util.List;
import java.util.Optional;

import org.arghyam.jalsoochak.message.dto.TenantProviderSecretRow;
import org.arghyam.jalsoochak.message.dto.TenantSecretKeyRow;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * PER-TENANT-PROVIDERS: read-only access to {@code common_schema.tenant_secret_key} and
 * {@code common_schema.tenant_provider_secret} (V44).
 *
 * <p>The read half of tenant-service's {@code TenantProviderSecretRepository}. Deliberately
 * read-only: every write, rotation and rewrap of the secret store belongs to tenant-service, which
 * owns the master key for wrapping. This service unwraps, and nothing here can create, change or
 * delete a row — so a compromise of the send path cannot alter what a tenant's credentials are,
 * only use them, which is the boundary O2-19 and S-8 were accepted on.
 *
 * <p>This class moves ciphertext only. It never sees a plaintext secret and never sees key
 * material, so nothing it could log would leak one. Every read filters {@code deleted_at IS NULL}:
 * a soft-deleted secret is gone as far as a send is concerned.
 *
 * <p>Failures are deliberately <em>not</em> swallowed here, unlike
 * {@link TenantProviderConfigRepository}. A missing row is an ordinary empty {@link Optional} and
 * means "this tenant has not stored that credential"; a database error is an exception, so it
 * cannot be mistaken for one. {@code TenantChannelProviders} turns both into the same system-default
 * fallback, but only after logging what actually happened.
 */
@Repository
@RequiredArgsConstructor
public class TenantProviderSecretRepository {

    private static final RowMapper<TenantSecretKeyRow> SECRET_KEY_ROW_MAPPER = (rs, rowNum) ->
            new TenantSecretKeyRow(
                    rs.getInt("tenant_id"),
                    rs.getInt("key_version"),
                    rs.getString("wrapped_key"),
                    rs.getString("master_key_id"),
                    rs.getString("status"));

    private static final RowMapper<TenantProviderSecretRow> SECRET_ROW_MAPPER = (rs, rowNum) ->
            new TenantProviderSecretRow(
                    rs.getInt("tenant_id"),
                    MessagingChannel.valueOf(rs.getString("channel")),
                    rs.getString("secret_name"),
                    rs.getString("ciphertext"),
                    rs.getInt("key_version"));

    private final JdbcTemplate jdbcTemplate;

    /**
     * Any key version of a tenant, active or retired.
     *
     * <p>Looked up by the version the secret row names rather than by "the active one": a retired
     * version is retired for <em>writing</em>, and a secret still pointing at it must stay readable
     * until it is re-encrypted, or a mid-rotation send would fail for no reason the operator caused.
     */
    public Optional<TenantSecretKeyRow> findKey(Integer tenantId, int keyVersion) {
        String sql = """
                SELECT tenant_id, key_version, wrapped_key, master_key_id, status
                  FROM common_schema.tenant_secret_key
                 WHERE tenant_id = ? AND key_version = ?
                """;
        return jdbcTemplate.query(sql, SECRET_KEY_ROW_MAPPER, tenantId, keyVersion).stream().findFirst();
    }

    /**
     * Every live secret on one channel of one tenant, in one query.
     *
     * <p>One query rather than one per name on purpose: a provider needs all of its credentials or
     * none of them, and fetching them together means the whole channel is judged against a single
     * consistent read of the table instead of a rotation landing between two of them.
     */
    public List<TenantProviderSecretRow> findByTenantAndChannel(Integer tenantId, MessagingChannel channel) {
        String sql = """
                SELECT tenant_id, channel, secret_name, ciphertext, key_version
                  FROM common_schema.tenant_provider_secret
                 WHERE tenant_id = ? AND channel = ? AND deleted_at IS NULL
                 ORDER BY secret_name
                """;
        return jdbcTemplate.query(sql, SECRET_ROW_MAPPER, tenantId, channel.name());
    }
}
