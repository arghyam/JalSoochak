package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowVisionReadingsRetryServiceTest {

    @Test
    void retriesTimeoutsAndReturnsSuccessfulResult() {
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        FlowVisionResult expected = FlowVisionResult.builder()
                .adjustedReading(new BigDecimal("123.4"))
                .qualityStatus("GOOD")
                .qualityConfidence(new BigDecimal("0.95"))
                .correlationId("corr-1")
                .build();

        when(flowVisionService.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenReturn(expected);

        FlowVisionReadingsRetryService service = newService(flowVisionService);

        FlowVisionResult actual = service.extractReading("https://example.com/img.jpg");

        assertEquals(expected, actual);
        verify(flowVisionService, times(3)).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @Test
    void returnsNullAfterRetriableFailuresAreExhausted() {
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        when(flowVisionService.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new ResourceAccessException("Read timed out"));

        FlowVisionReadingsRetryService service = newService(flowVisionService);

        FlowVisionResult actual = service.extractReading("https://example.com/img.jpg");

        assertNull(actual);
        verify(flowVisionService, times(3)).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @Test
    void doesNotRetryNonTransientClientErrors() {
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        when(flowVisionService.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad request"));

        FlowVisionReadingsRetryService service = newService(flowVisionService);

        assertThrows(HttpClientErrorException.class, () -> service.extractReading("https://example.com/img.jpg"));
        verify(flowVisionService).extractReadingOrThrow("https://example.com/img.jpg");
    }

    private FlowVisionReadingsRetryService newService(FlowVisionService flowVisionService) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .retryExceptions(ResourceAccessException.class)
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build();
        return new FlowVisionReadingsRetryService(
                flowVisionService,
                RetryRegistry.of(retryConfig),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()),
                BulkheadRegistry.of(bulkheadConfig)
        );
    }
}
