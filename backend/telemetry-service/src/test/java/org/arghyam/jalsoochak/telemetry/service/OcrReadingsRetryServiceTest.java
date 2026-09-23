package org.arghyam.jalsoochak.telemetry.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision.FlowVisionOcrExtractor;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrReadingsRetryServiceTest {

    @Test
    void retriesTimeoutsAndReturnsSuccessfulResult() {
        FlowVisionOcrExtractor flowVisionOcrExtractor = mock(FlowVisionOcrExtractor.class);
        OcrReadingResult expected = OcrReadingResult.builder()
                .adjustedReading(new BigDecimal("123.4"))
                .qualityStatus("GOOD")
                .qualityConfidence(new BigDecimal("0.95"))
                .correlationId("corr-1")
                .build();

        when(flowVisionOcrExtractor.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenReturn(expected);

        OcrReadingsRetryService service = newService(flowVisionOcrExtractor);

        OcrReadingResult actual = service.extractReading("https://example.com/img.jpg");

        assertEquals(expected, actual);
        verify(flowVisionOcrExtractor, times(3)).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @Test
    void throwsServiceUnavailableAfterRetriableFailuresAreExhausted() {
        FlowVisionOcrExtractor flowVisionOcrExtractor = mock(FlowVisionOcrExtractor.class);
        when(flowVisionOcrExtractor.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new ResourceAccessException("Read timed out"));

        OcrReadingsRetryService service = newService(flowVisionOcrExtractor);

        assertThrows(OcrReadingsUnavailableException.class,
                () -> service.extractReading("https://example.com/img.jpg"));
        verify(flowVisionOcrExtractor, times(3)).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @ParameterizedTest
    @MethodSource("retriableHttpExceptions")
    void retriesTransientHttpFailuresAndThrowsServiceUnavailable(RuntimeException transientException) {
        FlowVisionOcrExtractor flowVisionOcrExtractor = mock(FlowVisionOcrExtractor.class);
        when(flowVisionOcrExtractor.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(transientException);

        OcrReadingsRetryService service = newService(flowVisionOcrExtractor);

        assertThrows(OcrReadingsUnavailableException.class,
                () -> service.extractReading("https://example.com/img.jpg"));
        verify(flowVisionOcrExtractor, times(3)).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @Test
    void doesNotRetryNonTransientClientErrors() {
        FlowVisionOcrExtractor flowVisionOcrExtractor = mock(FlowVisionOcrExtractor.class);
        when(flowVisionOcrExtractor.extractReadingOrThrow("https://example.com/img.jpg"))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad request"));

        OcrReadingsRetryService service = newService(flowVisionOcrExtractor);

        assertThrows(HttpClientErrorException.class, () -> service.extractReading("https://example.com/img.jpg"));
        verify(flowVisionOcrExtractor).extractReadingOrThrow("https://example.com/img.jpg");
    }

    @Test
    void releasesBulkheadPermitBetweenRetryAttempts() throws Exception {
        FlowVisionOcrExtractor flowVisionOcrExtractor = mock(FlowVisionOcrExtractor.class);
        CountDownLatch firstAttemptFailed = new CountDownLatch(1);
        AtomicInteger firstCallAttempts = new AtomicInteger();
        OcrReadingResult firstResult = OcrReadingResult.builder()
                .adjustedReading(new BigDecimal("100"))
                .qualityStatus("GOOD")
                .build();
        OcrReadingResult secondResult = OcrReadingResult.builder()
                .adjustedReading(new BigDecimal("200"))
                .qualityStatus("GOOD")
                .build();

        when(flowVisionOcrExtractor.extractReadingOrThrow(anyString())).thenAnswer(invocation -> {
            String readingUrl = invocation.getArgument(0);
            if ("https://example.com/first.jpg".equals(readingUrl)
                    && firstCallAttempts.incrementAndGet() == 1) {
                firstAttemptFailed.countDown();
                throw new ResourceAccessException("Read timed out");
            }
            if ("https://example.com/first.jpg".equals(readingUrl)) {
                return firstResult;
            }
            return secondResult;
        });

        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig(1));
        OcrReadingsRetryService service = newService(
                flowVisionOcrExtractor,
                bulkheadRegistry,
                Duration.ofMillis(300)
        );
        // Same instance the service resolved, so a rename cannot leave this watching an idle bulkhead.
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(OcrReadingsRetryService.INSTANCE_NAME);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OcrReadingResult> firstFuture = executor.submit(
                    () -> service.extractReading("https://example.com/first.jpg")
            );
            assertTrue(firstAttemptFailed.await(1, TimeUnit.SECONDS));

            // The latch fires from inside the supplier, so the permit is still held when it returns — it
            // is only released as the failure unwinds out of the bulkhead. Wait for that release rather
            // than assume it, or the call below races the worker thread for the single permit.
            assertTrue(awaitFreePermit(bulkhead, Duration.ofSeconds(1)),
                    "the failed attempt should release its bulkhead permit before the retry backoff");

            OcrReadingResult secondActual = service.extractReading("https://example.com/second.jpg");
            OcrReadingResult firstActual = firstFuture.get(1, TimeUnit.SECONDS);

            assertEquals(secondResult, secondActual);
            assertEquals(firstResult, firstActual);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Waits for the bulkhead to report a free permit, which is the observable signal that a call released it. */
    private static boolean awaitFreePermit(Bulkhead bulkhead, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (bulkhead.getMetrics().getAvailableConcurrentCalls() > 0) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    private OcrReadingsRetryService newService(FlowVisionOcrExtractor flowVisionOcrExtractor) {
        return newService(flowVisionOcrExtractor, BulkheadRegistry.of(bulkheadConfig(10)), Duration.ZERO);
    }

    private OcrReadingsRetryService newService(FlowVisionOcrExtractor flowVisionOcrExtractor,
                                                      BulkheadRegistry bulkheadRegistry,
                                                      Duration waitDuration) {
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(waitDuration)
                .retryExceptions(OcrTransientFailures.retriableExceptions())
                .build();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .recordExceptions(OcrTransientFailures.retriableExceptions())
                .build();
        // Null registry: these tests exercise the default-provider path (null settings), which the retry
        // service routes straight to flowVisionOcrExtractor without consulting the registry.
        return new OcrReadingsRetryService(
                flowVisionOcrExtractor,
                null,
                RetryRegistry.of(retryConfig),
                CircuitBreakerRegistry.of(circuitBreakerConfig),
                bulkheadRegistry
        );
    }

    private static BulkheadConfig bulkheadConfig(int maxConcurrentCalls) {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)
                .build();
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
