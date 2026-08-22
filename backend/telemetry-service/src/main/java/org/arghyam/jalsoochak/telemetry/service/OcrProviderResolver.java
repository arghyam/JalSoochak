package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves which external AI/OCR provider a given tenant should use, from per-tenant config.
 *
 * <p>Reads the following keys from {@code common_schema.tenant_config_master_table} (via
 * {@link TenantConfigRepository}), each optional:
 * <ul>
 *   <li>{@code ocr_provider} — provider id (see {@link MeterReadingExtractor#providerId()})</li>
 *   <li>{@code ocr_url} — endpoint URL for that provider</li>
 *   <li>{@code ocr_api_key} — API key/token; a literal, or {@code env:VAR_NAME} to read from the
 *       environment instead of storing the secret in the DB</li>
 *   <li>{@code ocr_auth_header} — header name to carry the key (default {@code Authorization})</li>
 * </ul>
 *
 * <p>When a tenant sets <em>none</em> of these keys, {@link #resolve(Integer)} returns {@code null},
 * meaning "use the built-in FlowVision provider exactly as before" — so untouched tenants are byte-for-byte
 * unchanged. When any key is set, unspecified fields fall back to the global {@code flowvision.*} defaults.
 */
@Service
@Slf4j
public class OcrProviderResolver {

    private static final String KEY_PROVIDER = "ocr_provider";
    private static final String KEY_URL = "ocr_url";
    private static final String KEY_API_KEY = "ocr_api_key";
    private static final String KEY_AUTH_HEADER = "ocr_auth_header";
    private static final String ENV_PREFIX = "env:";

    private final TenantConfigRepository tenantConfigRepository;
    private final Environment environment;
    private final String defaultProviderId;
    private final String defaultEndpointUrl;
    private final String defaultApiKey;
    private final String defaultAuthHeader;

    public OcrProviderResolver(
            TenantConfigRepository tenantConfigRepository,
            Environment environment,
            @Value("${flowvision.default-provider:" + OcrProviderSettings.DEFAULT_PROVIDER_ID + "}") String defaultProviderId,
            @Value("${flowvision.url}") String defaultEndpointUrl,
            @Value("${flowvision.api-key:}") String defaultApiKey,
            @Value("${flowvision.auth-header:" + OcrProviderSettings.DEFAULT_AUTH_HEADER + "}") String defaultAuthHeader) {
        this.tenantConfigRepository = tenantConfigRepository;
        this.environment = environment;
        this.defaultProviderId = defaultProviderId;
        this.defaultEndpointUrl = defaultEndpointUrl;
        this.defaultApiKey = defaultApiKey;
        this.defaultAuthHeader = defaultAuthHeader;
    }

    /**
     * Per-tenant OCR settings, or {@code null} when the tenant has no {@code ocr_*} override (use the
     * built-in default provider path). {@code null} tenantId also yields {@code null}.
     */
    public OcrProviderSettings resolve(Integer tenantId) {
        if (tenantId == null) {
            return null;
        }
        Optional<String> provider = config(tenantId, KEY_PROVIDER);
        Optional<String> url = config(tenantId, KEY_URL);
        Optional<String> apiKey = config(tenantId, KEY_API_KEY);
        Optional<String> authHeader = config(tenantId, KEY_AUTH_HEADER);

        if (provider.isEmpty() && url.isEmpty() && apiKey.isEmpty() && authHeader.isEmpty()) {
            return null;
        }

        OcrProviderSettings settings = new OcrProviderSettings(
                provider.orElse(defaultProviderId),
                url.orElse(defaultEndpointUrl),
                resolveSecret(apiKey.orElse(blankToNull(defaultApiKey))),
                authHeader.orElse(defaultAuthHeader)
        );
        log.debug("Resolved OCR provider '{}' for tenantId={}", settings.providerId(), tenantId);
        return settings;
    }

    private Optional<String> config(Integer tenantId, String key) {
        return tenantConfigRepository.findConfigValue(tenantId, key)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    /** Dereferences an {@code env:VAR_NAME} secret to its environment value; passes literals through. */
    private String resolveSecret(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.startsWith(ENV_PREFIX)) {
            String varName = raw.substring(ENV_PREFIX.length()).trim();
            String value = varName.isEmpty() ? null : environment.getProperty(varName);
            if (value == null || value.isBlank()) {
                log.warn("OCR api key references env var '{}' which is unset/blank", varName);
                return null;
            }
            return value;
        }
        return raw;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
