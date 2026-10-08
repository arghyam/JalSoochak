package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
@Slf4j
public class OcrReadingsRetryService {

    /** Must match the {@code resilience4j.*.instances} keys in application.yml, or library defaults apply. */
    public static final String INSTANCE_NAME = "ocrReadings";
    /** Per-provider resilience instances are named "ocrReadings-<providerId>" for isolation + metrics. */
    static final String PROVIDER_INSTANCE_PREFIX = INSTANCE_NAME + "-";

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    /** Shared across all providers: a global cap on concurrent OCR calls protecting the ingestion threads. */
    private final Bulkhead bulkhead;
    /** Retry + circuit breaker for BFM's built-in default provider (the tuned {@value #INSTANCE_NAME} instance). */
    private final ResilienceBundle defaultBundle;
    /**
     * Per-provider retry + circuit breaker, isolating one AI backend's failures from another's. Each is
     * derived from the default instance's config, so tuning and the transient-exception predicates are
     * inherited identically while the open/closed state and metrics are independent.
     */
    private final Map<String, ResilienceBundle> providerBundles = new ConcurrentHashMap<>();

    public OcrReadingsRetryService(RetryRegistry retryRegistry,
                                   CircuitBreakerRegistry circuitBreakerRegistry,
                                   BulkheadRegistry bulkheadRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.bulkhead = bulkheadRegistry.bulkhead(INSTANCE_NAME);
        this.defaultBundle = new ResilienceBundle(
                retryRegistry.retry(INSTANCE_NAME),
                circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME));
    }

    /**
     * Resilient extraction through {@code extractor}, which the caller picked through
     * {@link OcrProviderRegistry}. {@code null} settings mean the extractor's own global defaults. Retry and
     * circuit breaker are isolated per provider so a failing backend trips only its own breaker; the
     * bulkhead (concurrency cap) is shared across providers.
     *
     * <p>The resilience instance follows the extractor that actually serves the call, not the id a tenant
     * configured: an unknown/mis-typed id has already degraded to the default provider in the registry,
     * and must then use the default breaker rather than spawn a phantom instance named after a provider
     * that never runs.
     */
    public OcrReadingResult extractReading(MeterReadingExtractor extractor, String readingUrl,
                                           OcrProviderSettings settings) {
        ResilienceBundle bundle = bundleFor(extractor);
        Supplier<OcrReadingResult> supplier = () -> extractor.extractReadingOrThrow(readingUrl, settings);
        Supplier<OcrReadingResult> resilientSupplier = Retry.decorateSupplier(
                bundle.retry(),
                CircuitBreaker.decorateSupplier(bundle.circuitBreaker(), Bulkhead.decorateSupplier(bulkhead, supplier))
        );

        try {
            return resilientSupplier.get();
        } catch (Exception ex) {
            if (OcrTransientFailures.isServiceUnavailable(ex)) {
                log.warn("OCR readings retry exhausted provider={} imageUrlHash={} reason={}",
                        providerLabel(extractor),
                        imageUrlHash(readingUrl),
                        sanitizeLogValue(ex.getMessage()));
                throw new OcrReadingsUnavailableException("OCR readings service is temporarily unavailable", ex);
            }
            throw ex;
        }
    }

    /**
     * The resilience bundle keyed on the RESOLVED provider. BFM's built-in provider —
     * where unknown/mis-typed ids also degrade — uses the shared, tuned {@value #INSTANCE_NAME} instances;
     * any other registered provider gets its own derived instances so its failures cannot open the default
     * breaker (and a typo cannot spawn a phantom breaker that never matches a real backend).
     */
    private ResilienceBundle bundleFor(MeterReadingExtractor extractor) {
        String key = providerKey(extractor);
        if (key == null) {
            return defaultBundle;
        }
        return providerBundles.computeIfAbsent(key, this::deriveBundle);
    }

    private ResilienceBundle deriveBundle(String providerKey) {
        String instanceName = PROVIDER_INSTANCE_PREFIX + providerKey;
        Retry retry = retryRegistry.retry(instanceName, defaultBundle.retry().getRetryConfig());
        CircuitBreaker circuitBreaker =
                circuitBreakerRegistry.circuitBreaker(instanceName, defaultBundle.circuitBreaker().getCircuitBreakerConfig());
        log.info("Created isolated OCR resilience instance '{}'", instanceName);
        return new ResilienceBundle(retry, circuitBreaker);
    }

    /** Normalised id of the resolved provider, or {@code null} for the default provider (default bundle). */
    private String providerKey(MeterReadingExtractor extractor) {
        if (extractor == null || extractor.providerId() == null) {
            return null;
        }
        String normalized = extractor.providerId().trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals(OcrProviderSettings.DEFAULT_PROVIDER_ID)) {
            return null;
        }
        return normalized;
    }

    private String providerLabel(MeterReadingExtractor extractor) {
        String key = providerKey(extractor);
        return key == null ? OcrProviderSettings.DEFAULT_PROVIDER_ID : key;
    }

    private String imageUrlHash(String readingUrl) {
        if (readingUrl == null || readingUrl.isBlank()) {
            return "n/a";
        }
        return Integer.toHexString(readingUrl.hashCode());
    }

    private String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private record ResilienceBundle(Retry retry, CircuitBreaker circuitBreaker) {
    }
}
