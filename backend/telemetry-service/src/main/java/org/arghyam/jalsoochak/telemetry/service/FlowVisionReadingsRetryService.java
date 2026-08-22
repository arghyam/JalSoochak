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
import java.util.function.Supplier;

@Service
@Slf4j
public class FlowVisionReadingsRetryService {

    static final String INSTANCE_NAME = "flowvisionReadings";
    private final FlowVisionService flowVisionService;
    private final OcrProviderRegistry ocrProviderRegistry;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public FlowVisionReadingsRetryService(FlowVisionService flowVisionService,
                                          OcrProviderRegistry ocrProviderRegistry,
                                          RetryRegistry retryRegistry,
                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                          BulkheadRegistry bulkheadRegistry) {
        this.flowVisionService = flowVisionService;
        this.ocrProviderRegistry = ocrProviderRegistry;
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.bulkhead = bulkheadRegistry.bulkhead(INSTANCE_NAME);
    }

    /** Resilient extraction against the global-default FlowVision endpoint. */
    public FlowVisionResult extractReading(String readingUrl) {
        return extractReading(readingUrl, null);
    }

    /**
     * Resilient extraction against the tenant-resolved provider. {@code null} settings use the built-in
     * default provider. The retry / circuit-breaker / bulkhead budget ({@value #INSTANCE_NAME}) is shared
     * across providers so any slow OCR backend is contained without starving telemetry ingestion.
     */
    public FlowVisionResult extractReading(String readingUrl, OcrProviderSettings settings) {
        Supplier<FlowVisionResult> supplier = () -> invokeExtractor(readingUrl, settings);
        Supplier<FlowVisionResult> resilientSupplier = Retry.decorateSupplier(
                retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, Bulkhead.decorateSupplier(bulkhead, supplier))
        );

        try {
            return resilientSupplier.get();
        } catch (Exception ex) {
            if (FlowVisionTransientFailures.isServiceUnavailable(ex)) {
                log.warn("FlowVision /readings retry exhausted imageUrlHash={} reason={}",
                        imageUrlHash(readingUrl),
                        sanitizeLogValue(ex.getMessage()));
                throw new FlowVisionReadingsUnavailableException("FlowVision readings service is temporarily unavailable", ex);
            }
            throw ex;
        }
    }

    private FlowVisionResult invokeExtractor(String readingUrl, OcrProviderSettings settings) {
        if (settings == null || ocrProviderRegistry == null) {
            return flowVisionService.extractReadingOrThrow(readingUrl);
        }
        return ocrProviderRegistry.get(settings.providerId()).extractReadingOrThrow(readingUrl, settings);
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
}
