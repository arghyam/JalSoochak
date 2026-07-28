package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.dto.response.RolloverPosition;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void extractReadingUsesReturnedIdAsRequestId() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(buildSuccessResponse("returned-id-123", "123.4", "black"), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertEquals("returned-id-123", result.getRequestId());
        assertEquals("corr-123", result.getCorrelationId());
        assertNotNull(restTemplate.getLastPayload().get("id"));
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

    @Test
    void extractReadingParsesRolloverMetadata() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(buildRolloverResponse("01234", "black", List.of(
                Map.of("position", 2, "selectedValue", 1, "selectedConfidence", "0.55",
                        "alternateValue", 2, "alternateConfidence", "0.45")
        )), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertTrue(result.isHasRollover());
        assertEquals("01234", result.getRawMeterReading());
        assertFalse(result.isRedLastDigit());
        // meterReading (adjustedReading) is still built from the selectedDigits, untouched by rollover metadata.
        assertEquals("1234", result.getAdjustedReading().toPlainString());
        assertEquals(1, result.getRolloverPositions().size());
        RolloverPosition pos = result.getRolloverPositions().get(0);
        assertEquals(2, pos.position());
        assertEquals(1, pos.selectedValue());
        assertEquals(2, pos.alternateValue());
        assertEquals(0, new java.math.BigDecimal("0.55").compareTo(pos.selectedConfidence()));
        assertEquals(0, new java.math.BigDecimal("0.45").compareTo(pos.alternateConfidence()));
    }

    @Test
    void extractReadingWithoutRolloverKeysYieldsNoRollover() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        restTemplate.enqueue(new ResponseEntity<>(buildSuccessResponse("123.4", "black"), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertFalse(result.isHasRollover());
        assertNotNull(result.getRolloverPositions());
        assertTrue(result.getRolloverPositions().isEmpty());
        assertEquals("123.4", result.getRawMeterReading());
    }

    @Test
    void extractReadingSkipsMalformedRolloverEntriesAndSupportsDigitAliases() {
        ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
        List<Map<String, Object>> positions = new java.util.ArrayList<>();
        positions.add(Map.of("position", 1, "selectedDigit", 9, "alternateDigit", 8)); // *Digit aliases
        positions.add(Map.of("position", 3)); // malformed — missing digits, skipped
        restTemplate.enqueue(new ResponseEntity<>(buildRolloverResponse("987", "black", positions), HttpStatus.OK));

        FlowVisionService service = new FlowVisionService(restTemplate, FLOW_VISION_URL);

        FlowVisionResult result = service.extractReading("https://image-url");

        assertNotNull(result);
        assertTrue(result.isHasRollover());
        assertEquals(1, result.getRolloverPositions().size());
        assertEquals(9, result.getRolloverPositions().get(0).selectedValue());
        assertEquals(8, result.getRolloverPositions().get(0).alternateValue());
    }

    private static Map<String, Object> buildRolloverResponse(String meterReading,
                                                             String lastDigitColor,
                                                             List<Map<String, Object>> rolloverPositions) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "SUCCESS");
        result.put("correlationId", "corr-123");
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("meterReading", meterReading);
        data.put("lastDigitColor", lastDigitColor);
        data.put("qualityStatus", "GOOD");
        data.put("qualityConfidence", "0.95");
        data.put("hasRollover", true);
        data.put("rolloverPositions", rolloverPositions);
        result.put("data", data);
        return Map.of("result", result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildSuccessResponse(String meterReading, String lastDigitColor) {
        return buildSuccessResponse(null, meterReading, lastDigitColor);
    }

    private static Map<String, Object> buildSuccessResponse(String id, String meterReading, String lastDigitColor) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "SUCCESS");
        result.put("correlationId", "corr-123");
        if (id != null) {
            result.put("id", id);
        }
        result.put("data", Map.of(
                "meterReading", meterReading,
                "lastDigitColor", lastDigitColor,
                "qualityStatus", "GOOD",
                "qualityConfidence", "0.95"
        ));
        return Map.of(
                "result", result
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
