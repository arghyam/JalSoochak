package org.arghyam.jalsoochak.message.repository;

import java.util.List;
import java.util.Optional;

import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.MessagingAllowedHosts;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PER-TENANT-PROVIDERS: reads a tenant's provider settings from
 * {@code common_schema.tenant_config_master_table}, where tenant-service writes them.
 *
 * <p>{@link JdbcTemplate} with schema-qualified SQL and the same query shape as
 * {@code MessageTemplateService.findConfigValue}, with one addition: {@code deleted_at IS NULL}.
 * tenant-service soft-deletes a config row when a state admin turns a channel off
 * ({@code DELETE /messaging-providers/{channel}}), so a read that ignored the column would keep
 * serving settings the tenant has already removed — the opposite of what the delete was for.
 *
 * <p>Every failure here is answered with {@link Optional#empty()} rather than an exception. A
 * settings read sits on the path of a login OTP and an invitation mail: a malformed row or a
 * database hiccup must cost that tenant its own provider, not the message. The caller treats empty
 * as "this tenant has no usable settings" and uses the system default (O2-9).
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class TenantProviderConfigRepository {

    /**
     * The tenant id system configuration is stored under. Mirrors tenant-service's
     * {@code TenantConstants.SYSTEM_TENANT_ID}; it is not a real tenant and never has settings of
     * its own.
     */
    static final int SYSTEM_TENANT_ID = 0;

    /** Matches tenant-service's {@code SystemConfigKeyEnum.MESSAGING_PROVIDER_ALLOWED_HOSTS}. */
    static final String ALLOWED_HOSTS_CONFIG_KEY = "MESSAGING_PROVIDER_ALLOWED_HOSTS";

    private static final String FIND_CONFIG_SQL = """
            SELECT config_value
              FROM common_schema.tenant_config_master_table
             WHERE tenant_id = ? AND config_key = ? AND deleted_at IS NULL
             ORDER BY updated_at DESC, id DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** A tenant's email provider settings, or empty when unset, unreadable or malformed. */
    public Optional<EmailProviderSettings> findEmailSettings(Integer tenantId) {
        return findSettings(tenantId, MessagingChannel.EMAIL, EmailProviderSettings.class);
    }

    /** A tenant's SMS provider settings, or empty when unset, unreadable or malformed. */
    public Optional<SmsProviderSettings> findSmsSettings(Integer tenantId) {
        return findSettings(tenantId, MessagingChannel.SMS, SmsProviderSettings.class);
    }

    /**
     * The platform's SMTP host allowlist.
     *
     * <p>Returns {@link MessagingAllowedHosts#NONE} when the key is unset or unreadable, so a
     * missing row allows nothing. The key is the one thing standing between a state admin's SMTP
     * settings and an arbitrary destination for that tenant's password, and a control that defaults
     * to "everything" until someone remembers to populate it is not a control.
     */
    public MessagingAllowedHosts findAllowedHosts() {
        Optional<String> stored = findConfigValue(SYSTEM_TENANT_ID, ALLOWED_HOSTS_CONFIG_KEY);
        if (stored.isEmpty()) {
            log.warn("[Providers] {} is not set; no tenant may use SMTP until a super user sets it",
                    ALLOWED_HOSTS_CONFIG_KEY);
            return MessagingAllowedHosts.NONE;
        }
        try {
            MessagingAllowedHosts hosts = objectMapper.readValue(stored.get(), MessagingAllowedHosts.class);
            return hosts == null ? MessagingAllowedHosts.NONE : hosts;
        } catch (JsonProcessingException e) {
            // The stored value is not echoed: it is a super-user-managed list of internal host
            // names, and a parse failure is diagnosed from tenant-service's side.
            log.error("[Providers] {} is malformed and is being treated as empty, so no tenant may use"
                    + " SMTP: {}", ALLOWED_HOSTS_CONFIG_KEY, e.getOriginalMessage());
            return MessagingAllowedHosts.NONE;
        }
    }

    private <T> Optional<T> findSettings(Integer tenantId, MessagingChannel channel, Class<T> type) {
        if (tenantId == null) {
            return Optional.empty();
        }
        Optional<String> stored = findConfigValue(tenantId, channel.getSettingsConfigKey());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(stored.get(), type));
        } catch (JsonProcessingException e) {
            // The row is not echoed. It holds no credential — the write side rejects one — but it
            // does hold an SMTP username, which is half of one.
            log.error("[Providers] Malformed {} for tenantId={}; falling back to the system default: {}",
                    channel.getSettingsConfigKey(), tenantId, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private Optional<String> findConfigValue(Integer tenantId, String configKey) {
        try {
            List<String> rows = jdbcTemplate.query(FIND_CONFIG_SQL,
                    (rs, rowNum) -> rs.getString("config_value"), tenantId, configKey);
            return rows.stream().findFirst().filter(value -> value != null && !value.isBlank());
        } catch (DataAccessException e) {
            log.error("[Providers] Failed to read {} for tenantId={}; falling back to the system"
                    + " default: {}", configKey, tenantId, e.getMessage());
            return Optional.empty();
        }
    }
}
