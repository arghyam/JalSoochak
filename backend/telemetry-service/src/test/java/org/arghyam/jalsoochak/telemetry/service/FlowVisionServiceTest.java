package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowVisionServiceTest {
    private static final String FLOW_VISION_URL = "https://example.com/flowvision/v1/extract-reading";

    @Test
    void extractReadingReturnsResultOnSuccess() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(buildSuccessResponse("123.4", "black"), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertNotNull(result.getRequestId());
        assertEquals("corr-123", result.getCorrelationId());
        assertEquals("123.4", result.getAdjustedReading().toPlainString());
        assertEquals(result.getRequestId(), restTemplate.getLastPayload().get("id"));
        assertEquals("https://image-url", restTemplate.getLastPayload().get("imageURL"));
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingTreatsRedLastDigitAsDecimal() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(buildSuccessResponse("004983", "red"), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertNotNull(result.getRequestId());
        assertEquals("corr-123", result.getCorrelationId());
        assertEquals("498.3", result.getAdjustedReading().toPlainString());
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingReturnsNullOnException() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new RestClientException("temporary"));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNull(result);
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingReturnsRejectionReasonOnProviderRejection() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(Map.of(
                "result", Map.of(
                        "status", "FAILED",
                        "correlationId", "corr-rejected",
                        "reason", "unclear image"
                )
        ), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertNotNull(result.getRequestId());
        assertNull(result.getAdjustedReading());
        assertEquals("corr-rejected", result.getCorrelationId());
        assertEquals("unclear image", result.getRejectionReason());
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingDoesNotUseStatusAsRejectionReason() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(Map.of(
                "result", Map.of(
                        "status", "FAILED",
                        "correlationId", "corr-rejected",
                        "data", Map.of("qualityStatus", "REJECTED")
                )
        ), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertNull(result.getAdjustedReading());
        assertEquals("corr-rejected", result.getCorrelationId());
        assertNull(result.getRejectionReason());
        assertEquals(1, restTemplate.getCallCount());
    }

    @Test
    void extractReadingReturnsErrorMsgOnFlowVisionErrorResponse() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                org.springframework.http.HttpHeaders.EMPTY,
                """
                        {
                            "id": "c4fcaf95-63a5-4237-8f15-7011f7358e5b",
                            "ts": "2026-07-06T21:33:41.065002",
                            "responseCode": "ERROR",
                            "statusCode": 500,
                            "error": {
                                "errorCode": 500,
                                "errorMsg": "Invalid URL '': No scheme supplied. Perhaps you meant https://?"
                            }
                        }
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8
        ));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("");

        assertNotNull(result);
        assertNotNull(result.getRequestId());
        assertNull(result.getAdjustedReading());
        assertEquals("Invalid URL '': No scheme supplied. Perhaps you meant https://?", result.getRejectionReason());
        assertEquals(1, restTemplate.getCallCount());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildSuccessResponse(String meterReading, String lastDigitColor) {
        return Map.of(
                "result", Map.of(
                        "status", "SUCCESS",
                        "correlationId", "corr-123",
                        "data", Map.of(
                                "meterReading", meterReading,
                                "lastDigitColor", lastDigitColor,
                                "qualityStatus", "GOOD",
                                "qualityConfidence", "0.95"
                        )
                )
        );
    }

    private static final class ScriptedRestTemplate extends RestTemplate {
        private final Deque<Object> scriptedResponses = new ArrayDeque<>();
        private int callCount;
        private Map<String, String> lastPayload;

        void enqueue(Object responseOrException) {
            scriptedResponses.addLast(responseOrException);
        }

        int getCallCount() {
            return callCount;
        }

        Map<String, String> getLastPayload() {
            return lastPayload;
        }

        @Override
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, org.springframework.http.HttpEntity<?> requestEntity, Class<T> responseType) throws RestClientException {
            throw new UnsupportedOperationException("URI-based overload not used");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, org.springframework.http.HttpEntity<?> requestEntity, Class<T> responseType, Object... uriVariables) throws RestClientException {
            callCount++;
            if (requestEntity != null && requestEntity.getBody() instanceof Map<?, ?> body) {
                lastPayload = body.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> String.valueOf(entry.getKey()),
                                entry -> String.valueOf(entry.getValue())
                        ));
            }
            Object next = scriptedResponses.removeFirst();
            if (next instanceof RestClientException exception) {
                throw exception;
            }
            return (ResponseEntity<T>) next;
        }
    }
}
