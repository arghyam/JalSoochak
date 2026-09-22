package org.arghyam.jalsoochak.message.enums;

import java.util.Set;

/**
 * A messaging channel whose provider a tenant may configure for itself.
 *
 * <p>PER-TENANT-PROVIDERS: the read-side twin of tenant-service's
 * {@code enums/MessagingChannel}. The constants, their {@code settingsConfigKey} strings and
 * their {@code secretNames} must match that copy exactly; {@link #forSettingsConfigKey} is the
 * one addition this read side needs. There is no shared library module in this repo — the five copies of
 * {@code PiiEncryptionService} are the standing precedent — and the two halves must agree
 * exactly: the {@code settingsConfigKey} strings are what tenant-service writes into
 * {@code TENANT_CONFIG_UPDATED} and what this service's cache eviction matches on, and the
 * {@code secretNames} are the locations the secret store derives. A divergence would show up
 * as a tenant whose settings are saved but whose cache never evicts. Change both together.
 *
 * <p>{@code WHATSAPP} is deliberately absent. Every tenant shares one Glific organisation
 * today, so a per-tenant WhatsApp credential has nothing to point at.
 */
public enum MessagingChannel {

    /** SendGrid ({@code apiKey}) or SMTP ({@code password}). */
    EMAIL("EMAIL_PROVIDER_SETTINGS", Set.of("apiKey", "password")),

    /** SMSCountry ({@code authKey}, {@code authToken}). */
    SMS("SMS_PROVIDER_SETTINGS", Set.of("authKey", "authToken"));

    /**
     * Name of the {@code TenantConfigKeyEnum} constant holding this channel's provider
     * settings, and the key this service reads from
     * {@code common_schema.tenant_config_master_table}.
     */
    private final String settingsConfigKey;

    private final Set<String> secretNames;

    MessagingChannel(String settingsConfigKey, Set<String> secretNames) {
        this.settingsConfigKey = settingsConfigKey;
        this.secretNames = secretNames;
    }

    public String getSettingsConfigKey() {
        return settingsConfigKey;
    }

    /** The secret names this channel's providers accept. Immutable. */
    public Set<String> getSecretNames() {
        return secretNames;
    }

    public boolean supportsSecret(String secretName) {
        return secretName != null && secretNames.contains(secretName);
    }

    /** The channel a {@code TENANT_CONFIG_UPDATED} config key belongs to, or {@code null}. */
    public static MessagingChannel forSettingsConfigKey(String configKey) {
        if (configKey == null) {
            return null;
        }
        String trimmed = configKey.trim();
        for (MessagingChannel channel : values()) {
            if (channel.settingsConfigKey.equals(trimmed)) {
                return channel;
            }
        }
        return null;
    }
}
