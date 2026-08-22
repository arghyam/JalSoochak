package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The resilient path must dispatch to the tenant-resolved provider (not the built-in FlowVision service)
 * when {@link OcrProviderSettings} are supplied.
 */
class FlowVisionReadingsRetryServiceProviderRoutingTest {

    @Test
    void routesToResolvedProviderWhenSettingsSupplied() {
        FlowVisionResult expected = FlowVisionResult.builder()
                .adjustedReading(new BigDecimal("42"))
                .qualityStatus("GOOD")
                .build();

        MeterReadingExtractor visionX = mock(MeterReadingExtractor.class);
        when(visionX.providerId()).thenReturn("vision-x");
        when(visionX.extractReadingOrThrow(eq("https://img"), any(OcrProviderSettings.class))).thenReturn(expected);

        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(visionX), "flowvision");

        FlowVisionReadingsRetryService service = new FlowVisionReadingsRetryService(
                flowVisionService,
                registry,
                RetryRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(),
                BulkheadRegistry.ofDefaults());

        OcrProviderSettings settings =
                new OcrProviderSettings("vision-x", "https://vision-x/extract", "key", "Authorization");

        FlowVisionResult actual = service.extractReading("https://img", settings);

        assertSame(expected, actual);
        verify(visionX).extractReadingOrThrow("https://img", settings);
        verifyNoInteractions(flowVisionService);
    }
}
