package org.arghyam.jalsoochak.message.enums;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The SMS providers a tenant may point its own account at.
 *
 * <p>PER-TENANT-PROVIDERS: the read-side twin of tenant-service's
 * {@code enums/SmsProviderType}. The constants, their wire names and their required secret
 * names must match that copy exactly; only the unknown-value path differs.
 *
 * @see EmailProviderType for why an unknown value returns {@code null} rather than throwing
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

    /** The secret names that must be resolvable before this provider can be built. Immutable. */
    public Set<String> getRequiredSecretNames() {
        return requiredSecretNames;
    }

    @JsonCreator
    public static SmsProviderType fromWireName(String value) {
        SmsProviderType type = fromWireNameOrNull(value);
        if (type == null) {
            throw new IllegalArgumentException("Unsupported SMS provider '" + value
                    + "'. Supported providers: " + SMSCOUNTRY.wireName);
        }
        return type;
    }

    /** As {@link #fromWireName}, but {@code null} rather than throwing on an unknown value. */
    public static SmsProviderType fromWireNameOrNull(String value) {
        if (value != null) {
            String normalised = value.trim().toLowerCase(Locale.ROOT);
            for (SmsProviderType type : values()) {
                if (type.wireName.equals(normalised)) {
                    return type;
                }
            }
        }
        return null;
    }
}
