package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowVisionServiceTest {

    @Test
    void extractReadingReturnsResultOnSuccess() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(buildSuccessResponse(), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate);

        Optional<FlowVisionResult> resultOpt = service.extractReading("https://image-url");

        assertTrue(resultOpt.isPresent());
        FlowVisionResult result = resultOpt.get();
        assertNotNull(result);
        assertEquals("corr-123", result.getCorrelationId());
        assertEquals("123.4", result.getAdjustedReading().toPlainString());
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingReturnsEmptyOnException() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new RestClientException("temporary"));

        FlowVisionService service = new FlowVisionService(restTemplate);

        Optional<FlowVisionResult> resultOpt = service.extractReading("https://image-url");

        assertFalse(resultOpt.isPresent());
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingThrowsForNonRetryable4xx() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        FlowVisionService service = new FlowVisionService(restTemplate);

        assertThrows(HttpClientErrorException.class,
                () -> service.extractReading("https://image-url"));
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingThrowsWhenResultKeyMissing() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate);

        assertThrows(IllegalArgumentException.class,
                () -> service.extractReading("https://image-url"));
    }

    @Test
    void extractReadingThrowsWhenStatusIsNotSuccess() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(
                Map.of("result", Map.of("status", "FAIL", "data", Map.of())),
                HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate);

        assertThrows(IllegalArgumentException.class,
                () -> service.extractReading("https://image-url"));
    }

    @Test
    void extractReadingGeneratesUuidWhenCorrelationIdMissing() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "SUCCESS");
        resultMap.put("data", Map.of("meterReading", "50.0", "qualityStatus", "GOOD"));
        restTemplate.enqueue(new ResponseEntity<>(Map.of("result", resultMap), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate);

        Optional<FlowVisionResult> result = service.extractReading("https://image-url");

        assertTrue(result.isPresent());
        assertNotNull(result.get().getCorrelationId());
        assertDoesNotThrow(() -> UUID.fromString(result.get().getCorrelationId()));
    }

    @Test
    void extractReadingRetriesTransientErrorAndExhausts() throws Exception {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new RestClientException("transient-1"));
        restTemplate.enqueue(new RestClientException("transient-2"));

        FlowVisionService service = new FlowVisionService(restTemplate);
        Field f = FlowVisionService.class.getDeclaredField("retryMaxAttempts");
        f.setAccessible(true);
        f.set(service, 2);

        Optional<FlowVisionResult> result = service.extractReading("https://image-url");

        assertFalse(result.isPresent());
        assertEquals(2, restTemplate.getCallCount());
    }

    @Test
    void extractReadingSucceedsOnRetry() throws Exception {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new RestClientException("transient"));
        restTemplate.enqueue(new ResponseEntity<>(buildSuccessResponse(), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate);
        Field f = FlowVisionService.class.getDeclaredField("retryMaxAttempts");
        f.setAccessible(true);
        f.set(service, 2);

        Optional<FlowVisionResult> result = service.extractReading("https://image-url");

        assertTrue(result.isPresent());
        assertEquals("corr-123", result.get().getCorrelationId());
        assertEquals(2, restTemplate.getCallCount());
    }

    @Test
    void validateRetryPropertiesThrowsOnZeroMaxAttempts() throws Exception {
        FlowVisionService service = new FlowVisionService(new ScriptedRestTemplate());
        Field f = FlowVisionService.class.getDeclaredField("retryMaxAttempts");
        f.setAccessible(true);
        f.set(service, 0);

        assertThrows(IllegalArgumentException.class, service::validateRetryProperties);
    }

    @Test
    void validateRetryPropertiesThrowsOnNegativeBackoff() throws Exception {
        FlowVisionService service = new FlowVisionService(new ScriptedRestTemplate());
        Field f = FlowVisionService.class.getDeclaredField("retryInitialBackoffMs");
        f.setAccessible(true);
        f.set(service, -1L);

        assertThrows(IllegalArgumentException.class, service::validateRetryProperties);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildSuccessResponse() {
        return Map.of(
                "result", Map.of(
                        "status", "SUCCESS",
                        "correlationId", "corr-123",
                        "data", Map.of(
                                "meterReading", "123.4",
                                "qualityStatus", "GOOD",
                                "qualityConfidence", "0.95"
                        )
                )
        );
    }

    private static final class ScriptedRestTemplate extends RestTemplate {
        private final Deque<Object> scriptedResponses = new ArrayDeque<>();
        private int callCount;

        void enqueue(Object responseOrException) {
            scriptedResponses.addLast(responseOrException);
        }

        int getCallCount() {
            return callCount;
        }

        @Override
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, org.springframework.http.HttpEntity<?> requestEntity, Class<T> responseType) throws RestClientException {
            throw new UnsupportedOperationException("URI-based overload not used");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, org.springframework.http.HttpEntity<?> requestEntity, Class<T> responseType, Object... uriVariables) throws RestClientException {
            callCount++;
            Object next = scriptedResponses.removeFirst();
            if (next instanceof RestClientException exception) {
                throw exception;
            }
            return (ResponseEntity<T>) next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, org.springframework.http.HttpEntity<?> requestEntity, ParameterizedTypeReference<T> responseType, Object... uriVariables) throws RestClientException {
            callCount++;
            Object next = scriptedResponses.removeFirst();
            if (next instanceof RestClientException exception) {
                throw exception;
            }
            return (ResponseEntity<T>) next;
        }
    }
}
