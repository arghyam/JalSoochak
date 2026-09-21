package org.arghyam.jalsoochak.tenant.enums;

import java.util.Set;

/**
 * A messaging channel whose provider a tenant may configure for itself.
 *
 * <p>MESSAGING-PROVIDER-SECRETS: each channel owns a fixed set of secret names. The
 * store derives a secret's location from {@code (tenantId, channel, secretName)}, so
 * the name is the only part a caller supplies and it is checked against
 * {@link #getSecretNames()} before anything is written. A caller can therefore never
 * reach a location outside its own tenant and channel.
 *
 * <p>{@code WHATSAPP} is deliberately absent. Every tenant shares one Glific
 * organisation today, so a per-tenant WhatsApp credential has nothing to point at; the
 * string is reserved in the {@code channel} column but is not addressable through the
 * API until that changes.
 */
public enum MessagingChannel {

    /** SendGrid ({@code apiKey}) or SMTP ({@code password}). */
    EMAIL("EMAIL_PROVIDER_SETTINGS", Set.of("apiKey", "password")),

    /** SMSCountry ({@code authKey}, {@code authToken}). */
    SMS("SMS_PROVIDER_SETTINGS", Set.of("authKey", "authToken"));

    /**
     * Name of the {@code TenantConfigKeyEnum} constant holding this channel's provider
     * settings. Held as a string because the two constants are added in a later change,
     * and a secret write already has to raise {@code TENANT_CONFIG_UPDATED} under the
     * key so message-service evicts the right cache entry.
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
}
