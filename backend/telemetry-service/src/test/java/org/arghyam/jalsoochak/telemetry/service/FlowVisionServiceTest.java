package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
