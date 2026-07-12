package org.arghyam.jalsoochak.telemetry.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.arghyam.jalsoochak.telemetry.service.FlowVisionTransientFailures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowVisionResilienceConfigTest {

    private final FlowVisionResilienceConfig config = new FlowVisionResilienceConfig();

    @Test
    void retryAndCircuitBreakerUseTransientFailureClassifier() {
        RetryConfig.Builder<Object> retryBuilder = RetryConfig.custom();
        CircuitBreakerConfig.Builder circuitBreakerBuilder = CircuitBreakerConfig.custom();

        config.flowVisionReadingsRetryExceptions().customize(retryBuilder);
        config.flowVisionReadingsCircuitBreakerExceptions().customize(circuitBreakerBuilder);

        RetryConfig retryConfig = retryBuilder.build();
        CircuitBreakerConfig circuitBreakerConfig = circuitBreakerBuilder.build();

        for (Class<? extends Throwable> exceptionClass : FlowVisionTransientFailures.retriableExceptions()) {
            RuntimeException exception = instantiate(exceptionClass);
            assertTrue(retryConfig.getExceptionPredicate().test(exception));
            assertTrue(circuitBreakerConfig.getRecordExceptionPredicate().test(exception));
        }

        HttpClientErrorException badRequest = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad request");
        assertFalse(retryConfig.getExceptionPredicate().test(badRequest));
        assertFalse(circuitBreakerConfig.getRecordExceptionPredicate().test(badRequest));
    }

    private RuntimeException instantiate(Class<? extends Throwable> exceptionClass) {
        if (exceptionClass == org.springframework.web.client.ResourceAccessException.class) {
            return new org.springframework.web.client.ResourceAccessException("Read timed out");
        }
        if (exceptionClass == org.springframework.web.client.HttpServerErrorException.BadGateway.class) {
            return serverError(HttpStatus.BAD_GATEWAY);
        }
        if (exceptionClass == org.springframework.web.client.HttpServerErrorException.ServiceUnavailable.class) {
            return serverError(HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (exceptionClass == org.springframework.web.client.HttpServerErrorException.GatewayTimeout.class) {
            return serverError(HttpStatus.GATEWAY_TIMEOUT);
        }
        if (exceptionClass == org.springframework.web.client.HttpClientErrorException.TooManyRequests.class) {
            return clientError(HttpStatus.TOO_MANY_REQUESTS);
        }
        throw new IllegalArgumentException("Unsupported exception: " + exceptionClass);
    }

    private HttpServerErrorException serverError(HttpStatus status) {
        return (HttpServerErrorException) HttpServerErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }

    private HttpClientErrorException clientError(HttpStatus status) {
        return (HttpClientErrorException) HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }
}
