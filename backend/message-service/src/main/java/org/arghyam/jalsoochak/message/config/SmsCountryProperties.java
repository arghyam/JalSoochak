package org.arghyam.jalsoochak.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The SMSCountry account the platform itself sends from, bound to {@code smscountry.*}.
 *
 * <p>PER-TENANT-PROVIDERS: these are the <em>system default</em> credentials (O2-4) — what every
 * tenant used before the feature existed, and what a tenant with no settings of its own, an event
 * with no tenant, and every send while the flag is off still use. A tenant that configures its own
 * SMSCountry account gets those values from
 * {@link org.arghyam.jalsoochak.message.dto.SmsProviderSettings} and the encrypted secret store
 * instead; only {@link #baseUrl()} is shared by both paths, because SMSCountry serves every
 * customer from one host and a per-tenant URL would only add an attacker-chosen destination for
 * the auth token (O2-13).
 *
 * <p>Replaces the seven {@code @Value} fields {@code SmsCountryService} carried while it was a
 * singleton {@code @Component}. Deliberately <em>not</em> {@code @Validated}: the binding happens
 * whatever {@code notification.sms.provider} names, so a required-field check here would newly
 * fail a deployment that does not use SMSCountry at all. {@code SmsConfig} makes the one check
 * that used to happen — a present {@code sender-id} — on the path where it applies.
 */
@ConfigurationProperties(prefix = "smscountry")
public record SmsCountryProperties(
        String baseUrl,
        String authKey,
        String authToken,
        String senderId,
        String dltPrincipalEntityId,
        String dltTemplateId,
        String dltHeaderId) {

    /** As the former {@code @Value} default on {@code SmsCountryService.baseUrl}. */
    public static final String DEFAULT_BASE_URL = "https://restapi.smscountry.com/v0.1";

    public SmsCountryProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    /** Credentials must not reach a log line, an exception message or a heap dump label (S-4). */
    @Override
    public String toString() {
        return "SmsCountryProperties(baseUrl=" + baseUrl + ", senderId=" + senderId + ")";
    }
}
