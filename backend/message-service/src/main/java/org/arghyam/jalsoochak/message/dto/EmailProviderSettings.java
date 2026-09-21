package org.arghyam.jalsoochak.message.dto;

import org.arghyam.jalsoochak.message.enums.EmailProviderType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PER-TENANT-PROVIDERS: a tenant's {@code EMAIL_PROVIDER_SETTINGS} config value, as stored by
 * tenant-service's {@code EmailProviderConfigDTO}.
 *
 * <p>Deliberately a record and deliberately lenient, which is the opposite of the write side. There
 * the DTO rejects any unknown property, because an unknown property in a settings write is a typo
 * or an attempt to smuggle a credential into the settings, and a 400 is the right answer. Here the
 * row has already been validated once and the writer may be a newer tenant-service than this
 * deployment; failing to parse would take a working tenant back to the system default over a field
 * this service does not need. So unknown properties are ignored, and every semantic check that
 * matters at send time — the provider matching its block, the SMTP host — is re-run by
 * {@code ProviderEndpointPolicy} and the factories.
 *
 * <p>No credentials: {@code apiKey} and {@code password} live in the encrypted secret store and are
 * resolved separately by {@code TenantSecretResolver} from a location the server derives (O2-8).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailProviderSettings(
        EmailProviderType provider,
        String fromAddress,
        String fromName,
        String logoImageUrl,
        SendGrid sendgrid,
        Smtp smtp) {

    /**
     * Template ids belong to the SendGrid account that owns them, so they are a tenant setting: an
     * id created in one account is meaningless in another.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendGrid(Templates templates) {
    }

    /** The five transactional templates this service sends. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Templates(
            String passwordReset,
            String reinvitation,
            String defaultInvitation,
            String superUserInvitation,
            String stateAdminInvitation) {

        /** True when every id is present, which is what the write side already enforces. */
        public boolean isComplete() {
            return notBlank(passwordReset) && notBlank(reinvitation) && notBlank(defaultInvitation)
                    && notBlank(superUserInvitation) && notBlank(stateAdminInvitation);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * Subject and body templates are absent on purpose: they stay in this service's
     * {@code application.yml} (O2-17). Only the connection belongs to the tenant, and the password
     * is in the secret store.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Smtp(String host, Integer port, String username, Boolean startTls) {
    }

    /**
     * The settings block the declared provider needs, or {@code null} when it is absent — which is
     * a build failure, not a parse failure, so the tenant falls back rather than the read throwing.
     */
    public Object blockForProvider() {
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case SENDGRID -> sendgrid;
            case SMTP -> smtp;
        };
    }

    /** Identity only, never a field that could be half a credential such as an SMTP username. */
    @Override
    public String toString() {
        return "EmailProviderSettings(provider=" + provider + ")";
    }
}
