package org.arghyam.jalsoochak.tenant.dto.internal;

/**
 * MESSAGING-PROVIDER-SETTINGS: rejects any JSON property a settings DTO does not declare.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} alone does not do this. That attribute is
 * Jackson's default; it means "this class does not opt out of failing", and then defers to the
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} deserialization feature — which Spring Boot's auto-configured
 * ObjectMapper turns off for the whole application. Under that mapper the annotation is inert and
 * an unknown property is silently dropped. A {@code @JsonAnySetter} that throws is the one
 * mechanism that holds regardless of how the mapper is configured, which matters here because the
 * property being dropped could be a credential someone tried to put in the settings.
 *
 * <p>This is {@code @JsonAnySetter} used for the opposite purpose to {@code MessageBrokerConfigDTO}
 * and {@code StateITSystemConfigDTO}, which collect unknown properties into a map. Do not copy
 * those here: an open settings object is exactly what the no-secrets-in-settings rule forbids.
 */
public final class UnknownPropertyGuard {

    private UnknownPropertyGuard() {
    }

    /**
     * @param property the unknown property's name — never its value, which may be a credential
     * @param context  human-readable name of the settings object, for the error message
     * @throws IllegalArgumentException always; surfaced as 400 by {@code GlobalExceptionHandler}
     */
    public static void reject(String property, String context) {
        throw new IllegalArgumentException("Unknown property '" + property + "' in " + context
                + ". Provider settings hold no credentials — store those with "
                + "PUT /api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets.");
    }
}
