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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.Set;
import java.util.function.Supplier;

@Service
@Slf4j
public class FlowVisionReadingsRetryService {

    private static final String INSTANCE_NAME = "flowvisionReadings";
    private static final Set<Class<?>> RETRIABLE_RESPONSE_EXCEPTIONS = Set.of(
            HttpServerErrorException.BadGateway.class,
            HttpServerErrorException.ServiceUnavailable.class,
            HttpServerErrorException.GatewayTimeout.class,
            HttpClientErrorException.TooManyRequests.class
    );

    private final FlowVisionService flowVisionService;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    public FlowVisionReadingsRetryService(FlowVisionService flowVisionService,
                                          RetryRegistry retryRegistry,
                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                          BulkheadRegistry bulkheadRegistry) {
        this.flowVisionService = flowVisionService;
        this.retry = retryRegistry.retry(INSTANCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.bulkhead = bulkheadRegistry.bulkhead(INSTANCE_NAME);
    }

    public FlowVisionResult extractReading(String readingUrl) {
        Supplier<FlowVisionResult> supplier = () -> flowVisionService.extractReadingOrThrow(readingUrl);
        Supplier<FlowVisionResult> resilientSupplier = Bulkhead.decorateSupplier(
                bulkhead,
                CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, supplier))
        );

        try {
            return resilientSupplier.get();
        } catch (Exception ex) {
            if (isRetriableFlowVisionFailure(ex)) {
                log.warn("FlowVision /readings retry exhausted imageUrlHash={} reason={}",
                        imageUrlHash(readingUrl),
                        sanitizeLogValue(ex.getMessage()));
                return null;
            }
            throw ex;
        }
    }

    private boolean isRetriableFlowVisionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ResourceAccessException) {
                return true;
            }
            if (current instanceof RestClientException) {
                for (Class<?> retriableException : RETRIABLE_RESPONSE_EXCEPTIONS) {
                    if (retriableException.isInstance(current)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
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
