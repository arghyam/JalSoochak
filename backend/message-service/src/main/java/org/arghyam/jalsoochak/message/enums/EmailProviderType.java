package org.arghyam.jalsoochak.message.enums;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The email providers a tenant may point its own account at.
 *
 * <p>PER-TENANT-PROVIDERS: the read-side twin of tenant-service's
 * {@code enums/EmailProviderType}, which is what validates a settings write. The constants,
 * their wire names and their required secret names must match that copy exactly — see
 * {@link MessagingChannel} for why. Only the unknown-value path below differs.
 *
 * <p>The wire form is lower case, matching {@code notification.mail.provider}, so a stored
 * tenant setting and the system default are written the same way.
 *
 * <p>Unlike tenant-service, an unknown provider reaching this service is not a bad request but
 * a settings value written by an older or newer tenant-service. {@link #fromWireNameOrNull}
 * exists for that read path: it returns {@code null} so the tenant falls back to the system
 * default (O2-9) instead of the whole settings row failing to parse.
 */
public enum EmailProviderType {

    /** SendGrid dynamic templates. */
    SENDGRID("sendgrid", Set.of("apiKey")),

    /** SMTP with STARTTLS or implicit TLS. */
    SMTP("smtp", Set.of("password"));

    private final String wireName;
    private final Set<String> requiredSecretNames;

    EmailProviderType(String wireName, Set<String> requiredSecretNames) {
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

    /** Accepts the wire name in any case, so {@code "SMTP"} and {@code "smtp"} both bind. */
    @JsonCreator
    public static EmailProviderType fromWireName(String value) {
        EmailProviderType type = fromWireNameOrNull(value);
        if (type == null) {
            throw new IllegalArgumentException("Unsupported email provider '" + value
                    + "'. Supported providers: " + SENDGRID.wireName + ", " + SMTP.wireName);
        }
        return type;
    }

    /** As {@link #fromWireName}, but {@code null} rather than throwing on an unknown value. */
    public static EmailProviderType fromWireNameOrNull(String value) {
        if (value != null) {
            String normalised = value.trim().toLowerCase(Locale.ROOT);
            for (EmailProviderType type : values()) {
                if (type.wireName.equals(normalised)) {
                    return type;
                }
            }
        }
        return null;
    }
}
