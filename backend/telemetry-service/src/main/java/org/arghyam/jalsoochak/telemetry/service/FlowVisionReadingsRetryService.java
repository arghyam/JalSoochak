package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
@Slf4j
public class FlowVisionReadingsRetryService {

    static final String INSTANCE_NAME = "flowvisionReadings";
    /** Per-provider resilience instances are named "flowvisionReadings-<providerId>" for isolation + metrics. */
    static final String PROVIDER_INSTANCE_PREFIX = INSTANCE_NAME + "-";

    private final FlowVisionService flowVisionService;
    private final OcrProviderRegistry ocrProviderRegistry;
    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    /** Shared across all providers: a global cap on concurrent OCR calls protecting the ingestion threads. */
    private final Bulkhead bulkhead;
    /** Retry + circuit breaker for the built-in default provider (the tuned {@value #INSTANCE_NAME} instance). */
    private final ResilienceBundle defaultBundle;
    /**
     * Per-provider retry + circuit breaker, isolating one AI backend's failures from another's. Each is
     * derived from the default instance's config, so tuning and the transient-exception predicates are
     * inherited identically while the open/closed state and metrics are independent.
     */
    private final Map<String, ResilienceBundle> providerBundles = new ConcurrentHashMap<>();

    public FlowVisionReadingsRetryService(FlowVisionService flowVisionService,
                                          OcrProviderRegistry ocrProviderRegistry,
                                          RetryRegistry retryRegistry,
                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                          BulkheadRegistry bulkheadRegistry) {
        this.flowVisionService = flowVisionService;
        this.ocrProviderRegistry = ocrProviderRegistry;
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.bulkhead = bulkheadRegistry.bulkhead(INSTANCE_NAME);
        this.defaultBundle = new ResilienceBundle(
                retryRegistry.retry(INSTANCE_NAME),
                circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME));
    }

    /** Resilient extraction against the global-default FlowVision endpoint. */
    public FlowVisionResult extractReading(String readingUrl) {
        return extractReading(readingUrl, null);
    }

    /**
     * Resilient extraction against the tenant-resolved provider. {@code null} settings use the built-in
     * default provider. Retry and circuit breaker are isolated per provider so a failing backend trips
     * only its own breaker; the bulkhead (concurrency cap) is shared across providers.
     */
    public FlowVisionResult extractReading(String readingUrl, OcrProviderSettings settings) {
        // Resolve the extractor once so the resilience instance follows the provider that actually serves
        // the call: an unknown/mis-typed id degrades to FlowVision in the registry, and must then use the
        // default breaker rather than spawning a phantom instance named after a provider that never runs.
        MeterReadingExtractor extractor = resolveExtractor(settings);
        ResilienceBundle bundle = bundleFor(extractor);
        Supplier<FlowVisionResult> supplier = () -> invokeExtractor(extractor, readingUrl, settings);
        Supplier<FlowVisionResult> resilientSupplier = Retry.decorateSupplier(
                bundle.retry(),
                CircuitBreaker.decorateSupplier(bundle.circuitBreaker(), Bulkhead.decorateSupplier(bulkhead, supplier))
        );

        try {
            return resilientSupplier.get();
        } catch (Exception ex) {
            if (FlowVisionTransientFailures.isServiceUnavailable(ex)) {
                log.warn("FlowVision /readings retry exhausted provider={} imageUrlHash={} reason={}",
                        providerLabel(extractor),
                        imageUrlHash(readingUrl),
                        sanitizeLogValue(ex.getMessage()));
                throw new FlowVisionReadingsUnavailableException("FlowVision readings service is temporarily unavailable", ex);
            }
            throw ex;
        }
    }

    /**
     * The provider that will actually serve the call: {@code null} settings (or a null registry, as in
     * some unit tests) use the built-in FlowVision service; otherwise the registry resolves the configured
     * id, degrading to the default provider for an unknown id.
     */
    private MeterReadingExtractor resolveExtractor(OcrProviderSettings settings) {
        if (settings == null || ocrProviderRegistry == null) {
            return flowVisionService;
        }
        return ocrProviderRegistry.get(settings.providerId());
    }

    /**
     * The resilience bundle keyed on the RESOLVED provider. The built-in {@code flowvision} provider —
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

    private FlowVisionResult invokeExtractor(MeterReadingExtractor extractor, String readingUrl, OcrProviderSettings settings) {
        if (settings == null || ocrProviderRegistry == null) {
            return flowVisionService.extractReadingOrThrow(readingUrl);
        }
        return extractor.extractReadingOrThrow(readingUrl, settings);
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
