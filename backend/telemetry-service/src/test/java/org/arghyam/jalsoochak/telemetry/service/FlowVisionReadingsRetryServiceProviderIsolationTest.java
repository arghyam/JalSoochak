package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A failing OCR provider must trip only its own circuit breaker; the built-in default provider (and any
 * other provider) keeps working. This is the payoff of per-provider resilience isolation.
 */
class FlowVisionReadingsRetryServiceProviderIsolationTest {

    private static final String URL = "https://img.example.com/a.jpg";

    @Test
    void failingProviderTripsOnlyItsOwnBreakerAndDefaultKeepsWorking() {
        // Default provider always succeeds.
        FlowVisionResult ok = FlowVisionResult.builder()
                .adjustedReading(new BigDecimal("100"))
                .qualityStatus("GOOD")
                .build();
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        when(flowVisionService.extractReadingOrThrow(anyString())).thenReturn(ok);

        // Provider "vision-x" always fails with a transient (recorded) error.
        MeterReadingExtractor visionX = mock(MeterReadingExtractor.class);
        when(visionX.providerId()).thenReturn("vision-x");
        when(visionX.extractReadingOrThrow(anyString(), any(OcrProviderSettings.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));
        OcrProviderRegistry providerRegistry =
                new OcrProviderRegistry(List.of(visionX), "flowvision");

        // Breaker opens after 2 failing calls; no retries so each extractReading == one breaker call.
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(FlowVisionTransientFailures.retriableExceptions())
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(1)
                .retryExceptions(FlowVisionTransientFailures.retriableExceptions())
                .build();

        FlowVisionReadingsRetryService service = new FlowVisionReadingsRetryService(
                flowVisionService,
                providerRegistry,
                RetryRegistry.of(retryConfig),
                CircuitBreakerRegistry.of(cbConfig),
                BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build()));

        OcrProviderSettings visionXSettings =
                new OcrProviderSettings("vision-x", "https://vision-x/extract", "k", "Authorization");

        // Two real failures open vision-x's breaker.
        assertThrows(FlowVisionReadingsUnavailableException.class, () -> service.extractReading(URL, visionXSettings));
        assertThrows(FlowVisionReadingsUnavailableException.class, () -> service.extractReading(URL, visionXSettings));
        verify(visionX, times(2)).extractReadingOrThrow(anyString(), any(OcrProviderSettings.class));

        // Breaker now OPEN: the next call short-circuits without invoking the extractor.
        assertThrows(FlowVisionReadingsUnavailableException.class, () -> service.extractReading(URL, visionXSettings));
        verify(visionX, times(2)).extractReadingOrThrow(anyString(), any(OcrProviderSettings.class));

        // The default provider's breaker is unaffected — it still succeeds.
        assertEquals(ok, service.extractReading(URL));
    }

    @Test
    void defaultProviderUsesTheSharedTunedInstance() {
        // Sanity: null settings resolve the shared "flowvisionReadings" breaker, not a per-provider one.
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        FlowVisionResult ok = FlowVisionResult.builder().adjustedReading(new BigDecimal("1")).build();
        when(flowVisionService.extractReadingOrThrow(anyString())).thenReturn(ok);

        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
        FlowVisionReadingsRetryService service = new FlowVisionReadingsRetryService(
                flowVisionService,
                new OcrProviderRegistry(List.of(), "flowvision"),
                RetryRegistry.ofDefaults(),
                cbRegistry,
                BulkheadRegistry.ofDefaults());

        service.extractReading(URL);

        CircuitBreaker defaultBreaker = cbRegistry.circuitBreaker(FlowVisionReadingsRetryService.INSTANCE_NAME);
        assertEquals(1, defaultBreaker.getMetrics().getNumberOfSuccessfulCalls());
    }
}
