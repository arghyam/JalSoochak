package org.arghyam.jalsoochak.telemetry.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsRetryService;
import org.arghyam.jalsoochak.telemetry.service.OcrTransientFailures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrResilienceConfigTest {

    private final OcrResilienceConfig config = new OcrResilienceConfig();

    @Test
    void retryAndCircuitBreakerUseTransientFailureClassifier() {
        RetryConfig.Builder<Object> retryBuilder = RetryConfig.custom();
        CircuitBreakerConfig.Builder circuitBreakerBuilder = CircuitBreakerConfig.custom();

        config.ocrReadingsRetryExceptions().customize(retryBuilder);
        config.ocrReadingsCircuitBreakerExceptions().customize(circuitBreakerBuilder);

        RetryConfig retryConfig = retryBuilder.build();
        CircuitBreakerConfig circuitBreakerConfig = circuitBreakerBuilder.build();

        for (Class<? extends Throwable> exceptionClass : OcrTransientFailures.retriableExceptions()) {
            RuntimeException exception = instantiate(exceptionClass);
            assertTrue(retryConfig.getExceptionPredicate().test(exception));
            assertTrue(circuitBreakerConfig.getRecordExceptionPredicate().test(exception));
        }

        HttpClientErrorException badRequest = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad request");
        assertFalse(retryConfig.getExceptionPredicate().test(badRequest));
        assertFalse(circuitBreakerConfig.getRecordExceptionPredicate().test(badRequest));
    }

    /**
     * Tuned values reach an instance only by name. A yml block under any other name is ignored and the
     * library defaults apply, with nothing failing — so all three blocks, the customizers and the service
     * must agree, and the blocks must bind the {@code OCR_READINGS_*} variables ops set.
     */
    @Test
    void tunedYamlInstancesAreTheOnesTheCodeLooksUp() throws IOException {
        String instance = OcrReadingsRetryService.INSTANCE_NAME;
        assertEquals(instance, config.ocrReadingsRetryExceptions().name());
        assertEquals(instance, config.ocrReadingsCircuitBreakerExceptions().name());

        Map<String, String> yaml = applicationYaml();
        for (String registry : List.of("retry", "circuitbreaker", "bulkhead")) {
            String prefix = "resilience4j." + registry + ".instances." + instance + ".";
            Map<String, String> tuned = yaml.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            assertFalse(tuned.isEmpty(), "application.yml tunes no " + registry + " instance named " + instance);
            tuned.forEach((key, value) -> assertTrue(
                    !value.startsWith("${") || value.startsWith("${OCR_READINGS_"),
                    key + " is bound to " + value));
        }
    }

    private static Map<String, String> applicationYaml() throws IOException {
        Map<String, String> properties = new HashMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                properties.put(name, String.valueOf(enumerable.getProperty(name)));
            }
        }
        return properties;
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
