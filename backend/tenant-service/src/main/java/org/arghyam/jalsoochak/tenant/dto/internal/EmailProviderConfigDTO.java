package org.arghyam.jalsoochak.tenant.dto.internal;

import org.arghyam.jalsoochak.tenant.enums.EmailProviderType;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SETTINGS: the {@code EMAIL_PROVIDER_SETTINGS} config value — which email
 * account a tenant's own mail is sent through.
 *
 * <p><strong>No secret values, and no references to one.</strong> The API key and the SMTP
 * password live in the encrypted secret store, and their location is derived by the server from
 * {@code (tenantId, channel, secretName)}. A reference field here would let the writer of a
 * settings value name a location — another tenant's credential, or a platform one — and have it
 * sent to a host of their choosing (O2-8).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} is deliberate and is the opposite of
 * {@link MessageBrokerConfigDTO}, which collects unknown properties through {@code @JsonAnySetter}.
 * An unknown property here is either a typo that would silently do nothing or an attempt to smuggle
 * a credential into the settings JSON; both should be a 400. Spring Boot's ObjectMapper disables
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, so the annotation is what enforces it.
 *
 * <p>Field-shape rules are bean validation, which runs because these settings arrive on a
 * dedicated {@code @Valid} endpoint rather than as a {@code JsonNode} through the generic config
 * API. Everything cross-field — the provider matching its settings block, the SMTP host allowlist,
 * TLS and the address check — is in {@code MessagingProviderSettingsValidator}, because those rules
 * need the system config and DNS.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "Email provider settings for one tenant. Never contains credentials.")
public final class EmailProviderConfigDTO implements ConfigValueDTO {

    public static final int MAX_NAME_LENGTH = 255;
    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_TEMPLATE_ID_LENGTH = 128;
    public static final int MAX_HOST_LENGTH = 253;

    @NotNull(message = "provider is required")
    @Schema(description = "Email provider", example = "smtp", allowableValues = {"sendgrid", "smtp"})
    private EmailProviderType provider;

    /**
     * Must be a verified sender in the tenant's own provider account. Neither SendGrid nor a state
     * SMTP relay will send for an address it has not verified, and the rejection arrives long after
     * this write, so the address is stored exactly as given rather than being normalised.
     */
    @NotBlank(message = "fromAddress is required")
    @Email(message = "fromAddress must be a valid email address")
    @Size(max = MAX_NAME_LENGTH, message = "fromAddress must not exceed " + MAX_NAME_LENGTH + " characters")
    private String fromAddress;

    @Size(max = MAX_NAME_LENGTH, message = "fromName must not exceed " + MAX_NAME_LENGTH + " characters")
    private String fromName;

    @Size(max = MAX_URL_LENGTH, message = "logoImageUrl must not exceed " + MAX_URL_LENGTH + " characters")
    @Schema(description = "Absolute https URL of the logo embedded in templated mail")
    private String logoImageUrl;

    /** Required when {@code provider} is {@code sendgrid}, rejected otherwise. */
    @Valid
    private SendGridSettings sendgrid;

    /** Required when {@code provider} is {@code smtp}, rejected otherwise. */
    @Valid
    private SmtpSettings smtp;


    /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        UnknownPropertyGuard.reject(property, "email provider settings");
    }

    /**
     * Template ids belong to the account that owns them, so they are a tenant setting rather than a
     * system property: a SendGrid dynamic template id is meaningless outside the account it was
     * created in.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(description = "SendGrid account settings")
    public static final class SendGridSettings {

        @NotNull(message = "sendgrid.templates is required")
        @Valid
        private Templates templates;

        /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            UnknownPropertyGuard.reject(property, "sendgrid settings");
        }
    }

    /**
     * The five transactional templates message-service sends. All are required: a tenant with a
     * partial set would send four kinds of mail from its own account and silently fall back for the
     * fifth, which is harder to diagnose than a refused write.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(description = "SendGrid dynamic template ids, one per transactional mail")
    public static final class Templates {

        @NotBlank(message = "sendgrid.templates.passwordReset is required")
        @Size(max = MAX_TEMPLATE_ID_LENGTH)
        private String passwordReset;

        @NotBlank(message = "sendgrid.templates.reinvitation is required")
        @Size(max = MAX_TEMPLATE_ID_LENGTH)
        private String reinvitation;

        @NotBlank(message = "sendgrid.templates.defaultInvitation is required")
        @Size(max = MAX_TEMPLATE_ID_LENGTH)
        private String defaultInvitation;

        @NotBlank(message = "sendgrid.templates.superUserInvitation is required")
        @Size(max = MAX_TEMPLATE_ID_LENGTH)
        private String superUserInvitation;

        @NotBlank(message = "sendgrid.templates.stateAdminInvitation is required")
        @Size(max = MAX_TEMPLATE_ID_LENGTH)
        private String stateAdminInvitation;

        /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            UnknownPropertyGuard.reject(property, "sendgrid.templates");
        }
    }

    /**
     * Subject and body templates are absent on purpose: they stay in message-service's
     * {@code application.yml} (O2-17). Only the connection belongs to the tenant.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(description = "SMTP relay settings. The password is in the secret store, not here.")
    public static final class SmtpSettings {

        /**
         * Checked against {@code MESSAGING_PROVIDER_ALLOWED_HOSTS} and resolved on write, so a
         * settings value cannot make message-service open a connection to an internal host or send
         * the tenant's SMTP password to a host of the writer's choice (O2-13).
         */
        @NotBlank(message = "smtp.host is required")
        @Size(max = MAX_HOST_LENGTH, message = "smtp.host must not exceed " + MAX_HOST_LENGTH + " characters")
        private String host;

        @NotNull(message = "smtp.port is required")
        @Min(value = 1, message = "smtp.port must be between 1 and 65535")
        @Max(value = 65535, message = "smtp.port must be between 1 and 65535")
        private Integer port;

        @NotBlank(message = "smtp.username is required")
        @Size(max = MAX_NAME_LENGTH, message = "smtp.username must not exceed " + MAX_NAME_LENGTH + " characters")
        private String username;

        /**
         * STARTTLS on a submission port. May be {@code false} only on the implicit-TLS port 465,
         * where the connection is already encrypted before the SMTP conversation starts.
         */
        @NotNull(message = "smtp.startTls is required")
        private Boolean startTls;

        /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            UnknownPropertyGuard.reject(property, "smtp settings");
        }
    }
}
