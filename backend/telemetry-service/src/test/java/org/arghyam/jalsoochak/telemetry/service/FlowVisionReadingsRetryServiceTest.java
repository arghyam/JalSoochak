package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void throwsServiceUnavailableAfterRetriableFailuresAreExhausted() {
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        when(flowVisionService.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new ResourceAccessException("Read timed out"));

        FlowVisionReadingsRetryService service = newService(flowVisionService);

        assertThrows(FlowVisionReadingsUnavailableException.class,
                () -> service.extractReading("https://example.com/img.jpg"));
        verify(flowVisionService, times(3)).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @ParameterizedTest
    @MethodSource("retriableHttpExceptions")
    void retriesTransientHttpFailuresAndThrowsServiceUnavailable(RuntimeException transientException) {
        FlowVisionService flowVisionService = mock(FlowVisionService.class);
        when(flowVisionService.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(transientException);

        FlowVisionReadingsRetryService service = newService(flowVisionService);

        assertThrows(FlowVisionReadingsUnavailableException.class,
                () -> service.extractReading("https://example.com/img.jpg"));
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
                .retryExceptions(FlowVisionTransientFailures.retriableExceptions())
                .build();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .recordExceptions(FlowVisionTransientFailures.retriableExceptions())
                .build();
        return new FlowVisionReadingsRetryService(
                flowVisionService,
                RetryRegistry.of(retryConfig),
                CircuitBreakerRegistry.of(circuitBreakerConfig),
                BulkheadRegistry.of(bulkheadConfig)
        );
    }

    private static Stream<RuntimeException> retriableHttpExceptions() {
        return Stream.of(
                serverError(HttpStatus.BAD_GATEWAY),
                serverError(HttpStatus.SERVICE_UNAVAILABLE),
                serverError(HttpStatus.GATEWAY_TIMEOUT),
                clientError(HttpStatus.TOO_MANY_REQUESTS)
        );
    }

    private static HttpServerErrorException serverError(HttpStatus status) {
        return (HttpServerErrorException) HttpServerErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return (HttpClientErrorException) HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }
}
