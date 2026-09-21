package org.arghyam.jalsoochak.tenant.dto.internal;

import org.arghyam.jalsoochak.tenant.enums.SmsProviderType;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MESSAGING-PROVIDER-SETTINGS: the {@code SMS_PROVIDER_SETTINGS} config value — which SMS account
 * a tenant's own messages are sent through.
 *
 * <p>No credentials here: {@code authKey} and {@code authToken} are in the encrypted secret store
 * (O2-8). The API base URL is not here either — SMSCountry serves every customer from one host and
 * identifies the account by the credentials, so a per-tenant URL would buy nothing and would add an
 * attacker-chosen destination for the auth token (O2-13).
 *
 * <p>DLT registrations belong to the sender id, and each tenant registers under its own entity, so
 * the DLT ids and the OTP text are tenant settings rather than system properties.
 *
 * @see EmailProviderConfigDTO for why unknown properties are rejected
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "SMS provider settings for one tenant. Never contains credentials.")
public final class SmsProviderConfigDTO implements ConfigValueDTO {

    public static final int MAX_ID_LENGTH = 128;

    /**
     * The default OTP text, character for character what {@code SmsCountryService} sends today, with
     * its two {@code String.formatted} placeholders rewritten as named ones. A tenant that does not
     * set {@code otpTemplate} keeps exactly today's message.
     */
    public static final String DEFAULT_OTP_TEMPLATE =
            "Your OTP for Jalsoochak login is {otp}. Do not share this OTP. Valid for {expiryMinutes} minutes.";

    /**
     * Two GSM-7 segments. Long enough for every DLT-registered OTP text seen so far and short
     * enough that a stored value cannot quietly turn one billable SMS into ten.
     */
    public static final int MAX_OTP_TEMPLATE_LENGTH = 320;

    @NotNull(message = "provider is required")
    @Schema(description = "SMS provider", example = "smscountry", allowableValues = {"smscountry"})
    private SmsProviderType provider;

    /** Required when {@code provider} is {@code smscountry}. */
    @Valid
    private SmsCountrySettings smscountry;

    /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        UnknownPropertyGuard.reject(property, "SMS provider settings");
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(description = "SMSCountry account settings. The auth key and token are in the secret store.")
    public static final class SmsCountrySettings {

        @NotBlank(message = "smscountry.senderId is required")
        @Size(max = MAX_ID_LENGTH, message = "smscountry.senderId must not exceed " + MAX_ID_LENGTH + " characters")
        @Schema(description = "Registered sender id / header", example = "JLSCHK")
        private String senderId;

        @NotBlank(message = "smscountry.dltPrincipalEntityId is required")
        @Size(max = MAX_ID_LENGTH,
                message = "smscountry.dltPrincipalEntityId must not exceed " + MAX_ID_LENGTH + " characters")
        private String dltPrincipalEntityId;

        @NotBlank(message = "smscountry.dltTemplateId is required")
        @Size(max = MAX_ID_LENGTH,
                message = "smscountry.dltTemplateId must not exceed " + MAX_ID_LENGTH + " characters")
        private String dltTemplateId;

        @NotBlank(message = "smscountry.dltHeaderId is required")
        @Size(max = MAX_ID_LENGTH,
                message = "smscountry.dltHeaderId must not exceed " + MAX_ID_LENGTH + " characters")
        private String dltHeaderId;

        /**
         * The OTP text, with named placeholders {@code {otp}} (required, once) and
         * {@code {expiryMinutes}} (optional, at most once). Null means
         * {@link SmsProviderConfigDTO#DEFAULT_OTP_TEMPLATE}.
         *
         * <p>Named rather than {@code %s}/{@code %d}: today's template is applied with
         * {@code String.formatted}, so a stored value with an extra or mistyped conversion would
         * throw {@code IllegalFormatException} at send time, or silently consume the wrong
         * argument — a failure on the OTP path, discovered by a user who cannot log in (O2-15).
         *
         * <p>The text must also match what the tenant registered on the DLT portal under this
         * sender id. Nothing here can check that; a mismatch is dropped by the operator, not
         * rejected by SMSCountry.
         */
        @Size(max = MAX_OTP_TEMPLATE_LENGTH,
                message = "smscountry.otpTemplate must not exceed " + MAX_OTP_TEMPLATE_LENGTH + " characters")
        @Schema(description = "OTP message text with {otp} and optional {expiryMinutes} placeholders",
                example = DEFAULT_OTP_TEMPLATE)
        private String otpTemplate;

        /** @see UnknownPropertyGuard — an unknown property here is a 400, never a dropped field. */
        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            UnknownPropertyGuard.reject(property, "smscountry settings");
        }
    }
}
