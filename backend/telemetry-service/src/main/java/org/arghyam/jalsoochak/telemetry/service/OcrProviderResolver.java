package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Resolves which external AI/OCR provider a tenant uses for a channel's photos, from per-tenant config.
 *
 * <p>One set of keys points at one model endpoint, which reads one kind of meter, so each channel that
 * reads photos has its own set in {@code common_schema.tenant_config_master_table} (via
 * {@link TenantConfigRepository}), each optional:
 * <ul>
 *   <li>{@code ocr_provider} / {@code ocr_elm_provider} — provider id (see
 *       {@link MeterReadingExtractor#providerId()})</li>
 *   <li>{@code ocr_url} / {@code ocr_elm_url} — endpoint URL for that provider</li>
 *   <li>{@code ocr_api_key} / {@code ocr_elm_api_key} — API key/token; a literal, or
 *       {@code env:VAR_NAME} to read from the environment instead of storing the secret in the DB</li>
 *   <li>{@code ocr_auth_header} / {@code ocr_elm_auth_header} — header name to carry the key (default
 *       {@code Authorization})</li>
 * </ul>
 * The {@code ocr_*} keys are BFM's. PDU readings are always typed in, so PDU has no keys.
 *
 * <p>When a tenant sets <em>none</em> of a channel's keys, {@link #resolve(Integer, ReadingChannel)}
 * returns {@code null}, meaning "use the channel's default provider with its own configuration". When any
 * BFM key is set, unspecified fields fall back to the global {@code ocr.*} defaults, as they always have.
 * ELM fields never do: the global settings point at the BFM model, and its key must not be sent to
 * another endpoint.
 */
@Service
@Slf4j
public class OcrProviderResolver {

    private static final ConfigKeys BFM_KEYS = ConfigKeys.withPrefix("ocr_");
    private static final ConfigKeys ELM_KEYS = ConfigKeys.withPrefix("ocr_elm_");
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
            @Value("${ocr.default-provider:" + OcrProviderSettings.DEFAULT_PROVIDER_ID + "}") String defaultProviderId,
            @Value("${ocr.url}") String defaultEndpointUrl,
            @Value("${ocr.api-key:}") String defaultApiKey,
            @Value("${ocr.auth-header:" + OcrProviderSettings.DEFAULT_AUTH_HEADER + "}") String defaultAuthHeader) {
        this.tenantConfigRepository = tenantConfigRepository;
        this.environment = environment;
        this.defaultProviderId = defaultProviderId;
        this.defaultEndpointUrl = defaultEndpointUrl;
        this.defaultApiKey = defaultApiKey;
        this.defaultAuthHeader = defaultAuthHeader;
    }

    /**
     * The tenant's OCR settings for {@code channel}, or {@code null} when the tenant has no override for
     * it (use the channel's default provider). A {@code null} tenantId, and a channel that doesn't read
     * photos, also yield {@code null} without reading any config.
     */
    public OcrProviderSettings resolve(Integer tenantId, ReadingChannel channel) {
        if (tenantId == null || channel == null) {
            return null;
        }
        OcrProviderSettings settings = switch (channel) {
            case BFM -> resolveWithGlobalDefaults(tenantId);
            case ELM -> resolveWithoutGlobalDefaults(tenantId, ELM_KEYS);
            case PDU, IOT, MAN -> null;
        };
        if (settings != null) {
            log.debug("Resolved OCR provider '{}' for tenantId={} channel={}", settings.providerId(), tenantId, channel);
        }
        return settings;
    }

    private OcrProviderSettings resolveWithGlobalDefaults(Integer tenantId) {
        TenantValues values = read(tenantId, BFM_KEYS);
        if (values.isEmpty()) {
            return null;
        }

        String resolvedUrl = values.url() != null ? values.url() : defaultEndpointUrl;
        // Only inherit the global OCR key when the endpoint is still the default one; a tenant that
        // points at a custom endpoint without its own ocr_api_key must NOT have the default key sent there.
        boolean endpointIsDefault = resolvedUrl.equals(defaultEndpointUrl);
        String rawApiKey = values.apiKey() != null
                ? values.apiKey()
                : endpointIsDefault ? blankToNull(defaultApiKey) : null;

        return new OcrProviderSettings(
                values.provider() != null ? values.provider() : defaultProviderId,
                resolvedUrl,
                resolveSecret(rawApiKey),
                values.authHeader() != null ? values.authHeader() : defaultAuthHeader
        );
    }

    /** A field the tenant didn't set stays null, which leaves it to the extractor's own configuration. */
    private OcrProviderSettings resolveWithoutGlobalDefaults(Integer tenantId, ConfigKeys keys) {
        TenantValues values = read(tenantId, keys);
        if (values.isEmpty()) {
            return null;
        }
        return new OcrProviderSettings(
                values.provider(), values.url(), resolveSecret(values.apiKey()), values.authHeader());
    }

    private TenantValues read(Integer tenantId, ConfigKeys keys) {
        return new TenantValues(
                config(tenantId, keys.provider()),
                config(tenantId, keys.url()),
                config(tenantId, keys.apiKey()),
                config(tenantId, keys.authHeader()));
    }

    /** The trimmed value of {@code key}, or {@code null} when the tenant didn't set it. */
    private String config(Integer tenantId, String key) {
        return tenantConfigRepository.findConfigValue(tenantId, key)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
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

    /** The four config keys of one channel. */
    private record ConfigKeys(String provider, String url, String apiKey, String authHeader) {
        static ConfigKeys withPrefix(String prefix) {
            return new ConfigKeys(prefix + "provider", prefix + "url", prefix + "api_key", prefix + "auth_header");
        }
    }

    /** What a tenant set for one channel's keys; a key it didn't set is null. */
    private record TenantValues(String provider, String url, String apiKey, String authHeader) {
        boolean isEmpty() {
            return provider == null && url == null && apiKey == null && authHeader == null;
        }
    }
}
