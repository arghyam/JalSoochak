package org.arghyam.jalsoochak.tenant.enums;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MESSAGING-PROVIDER-SETTINGS: the email providers a tenant may point its own account at.
 *
 * <p>The wire form is lower case, matching message-service's {@code notification.mail.provider}
 * property, so a stored tenant setting and the system default are written the same way and an
 * operator moving a value between them does not have to translate it.
 *
 * <p>Each constant names the secrets its provider needs. That is the link between a settings
 * write and {@link MessagingChannel#getSecretNames()}: the settings say which credential must
 * be present before the tenant's account can be used, and the secret store says whether it is.
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

    /** The secret names that must be SET before message-service can build this provider. Immutable. */
    public Set<String> getRequiredSecretNames() {
        return requiredSecretNames;
    }

    /**
     * Accepts the wire name in any case, so {@code "SMTP"} and {@code "smtp"} both bind. An
     * unknown value throws, which the config endpoints surface as 400 rather than storing a
     * provider message-service would later fail to build.
     */
    @JsonCreator
    public static EmailProviderType fromWireName(String value) {
        if (value != null) {
            String normalised = value.trim().toLowerCase(Locale.ROOT);
            for (EmailProviderType type : values()) {
                if (type.wireName.equals(normalised)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported email provider '" + value
                + "'. Supported providers: " + SENDGRID.wireName + ", " + SMTP.wireName);
    }
}
