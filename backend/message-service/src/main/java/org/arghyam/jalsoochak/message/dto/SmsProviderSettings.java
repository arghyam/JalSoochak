package org.arghyam.jalsoochak.message.dto;

import org.arghyam.jalsoochak.message.enums.SmsProviderType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * PER-TENANT-PROVIDERS: a tenant's {@code SMS_PROVIDER_SETTINGS} config value, as stored by
 * tenant-service's {@code SmsProviderConfigDTO}.
 *
 * @see EmailProviderSettings for why the read side ignores unknown properties while the write side
 *      rejects them, and why {@code provider} is the raw wire name rather than the enum
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SmsProviderSettings(String provider, SmsCountry smscountry) {

    /**
     * The DLT registrations belong to the sender id and each tenant registers under its own entity,
     * so they are tenant settings rather than system properties. The auth key and token are not
     * here — they are in the encrypted secret store, and the API base URL stays a system property
     * so a tenant setting cannot choose where the token is sent (O2-13).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SmsCountry(
            String senderId,
            String dltPrincipalEntityId,
            String dltTemplateId,
            String dltHeaderId,
            String otpTemplate) {

        /**
         * The OTP text a tenant that set none should get: character for character what
         * {@code SmsCountrySender} sends today, with its two {@code String.formatted} placeholders
         * written as the named ones tenant-service validates (O2-15). Duplicated from
         * {@code SmsProviderConfigDTO.DEFAULT_OTP_TEMPLATE}; the two must stay equal, or a tenant
         * would see a different message depending on whether the field was stored or defaulted.
         */
        public static final String DEFAULT_OTP_TEMPLATE =
                "Your OTP for Jalsoochak login is {otp}. Do not share this OTP. "
                        + "Valid for {expiryMinutes} minutes.";

        /** The stored template, or the default when the tenant left it unset. */
        public String otpTemplateOrDefault() {
            return otpTemplate == null || otpTemplate.isBlank() ? DEFAULT_OTP_TEMPLATE : otpTemplate;
        }
    }

    /**
     * The stored {@link #provider} as a known type, or {@code null} when it is absent, blank or
     * names a provider this deployment does not support.
     */
    public SmsProviderType providerType() {
        return SmsProviderType.fromWireNameOrNull(provider);
    }

    /** The settings block the declared provider needs, or {@code null} when it is absent. */
    public Object blockForProvider() {
        SmsProviderType type = providerType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case SMSCOUNTRY -> smscountry;
        };
    }

    /** Identity only — the sender and DLT ids are not secret but have no place in a log line. */
    @Override
    public String toString() {
        return "SmsProviderSettings(provider=" + provider + ")";
    }
}
