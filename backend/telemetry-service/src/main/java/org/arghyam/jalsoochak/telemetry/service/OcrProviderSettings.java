package org.arghyam.jalsoochak.telemetry.service;

/**
 * Resolved, per-tenant configuration for the external meter-reading AI/OCR provider.
 *
 * <p>Produced by {@link OcrProviderResolver} from the tenant's {@code ocr_*} config keys (falling back
 * to the global {@code flowvision.*} defaults) and consumed by a {@link MeterReadingExtractor} selected
 * via {@link OcrProviderRegistry}. This is what makes the AI service pluggable per state/tenant: which
 * provider to call ({@link #providerId()}), where ({@link #endpointUrl()}), and how to authenticate
 * ({@link #apiKey()} / {@link #authHeaderName()}) are all data, not code.
 */
public record OcrProviderSettings(
        String providerId,
        String endpointUrl,
        String apiKey,
        String authHeaderName
) {

    /** Provider id of the built-in FlowVision extractor and the default when a tenant configures none. */
    public static final String DEFAULT_PROVIDER_ID = "flowvision";

    /** Header used to carry {@link #apiKey()} when no per-tenant override is set. */
    public static final String DEFAULT_AUTH_HEADER = "Authorization";

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The configured auth header name, or {@link #DEFAULT_AUTH_HEADER} when blank/unset. */
    public String resolvedAuthHeaderName() {
        return (authHeaderName == null || authHeaderName.isBlank()) ? DEFAULT_AUTH_HEADER : authHeaderName;
    }
}
