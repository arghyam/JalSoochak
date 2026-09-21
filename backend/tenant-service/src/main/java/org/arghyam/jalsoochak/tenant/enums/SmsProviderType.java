package org.arghyam.jalsoochak.tenant.enums;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MESSAGING-PROVIDER-SETTINGS: the SMS providers a tenant may point its own account at.
 *
 * <p>One constant today. It is an enum rather than a free string so that adding a second
 * vendor is a compile-time change, and so a typo is rejected on write instead of being stored
 * as a provider message-service cannot build.
 *
 * @see EmailProviderType
 */
public enum SmsProviderType {

    /** SMSCountry REST API. */
    SMSCOUNTRY("smscountry", Set.of("authKey", "authToken"));

    private final String wireName;
    private final Set<String> requiredSecretNames;

    SmsProviderType(String wireName, Set<String> requiredSecretNames) {
        this.wireName = wireName;
        this.requiredSecretNames = requiredSecretNames;
    }

    @JsonValue
    public String getWireName() {
        return wireName;
    }

    /** The secret names that must be SET before message-service can build this provider. Immutable. */
    public Set<String> getRequiredSecretNames() {
        return requiredSecretNames;
    }

    @JsonCreator
    public static SmsProviderType fromWireName(String value) {
        if (value != null) {
            String normalised = value.trim().toLowerCase(Locale.ROOT);
            for (SmsProviderType type : values()) {
                if (type.wireName.equals(normalised)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported SMS provider '" + value
                + "'. Supported providers: " + SMSCOUNTRY.wireName);
    }
}
