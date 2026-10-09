package org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrTransientFailures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ELM OCR extractor — maps the ELM meter-reading service onto OcrReadingResult")
class FlowVisionElmOcrExtractorTest {

    private static final String ELM_URL = "https://meter-reader.example/meter-reader/v1/extract-reading";
    private static final String GLOBAL_API_KEY = "global-elm-key";
    private static final String IMAGE_URL = "https://images.example/photos/000123.jpg";

    private final ScriptedRestTemplate restTemplate = new ScriptedRestTemplate();
    private final FlowVisionElmOcrExtractor extractor =
            new FlowVisionElmOcrExtractor(restTemplate, ELM_URL, GLOBAL_API_KEY, "X-API-Key", "");

    @Test
    void readsElmPhotosUnderItsOwnProviderId() {
        assertThat(extractor.channel()).isEqualTo(ReadingChannel.ELM);
        assertThat(extractor.providerId()).isEqualTo("flowvision-elm");
        // BFM's id keys the default resilience instance; sharing it would put ELM on BFM's breaker.
        assertThat(extractor.providerId()).isNotEqualToIgnoringCase(OcrProviderSettings.DEFAULT_PROVIDER_ID);
    }

    @Test
    void sendsOnlyAnIdDerivedFromTheImageUrlAndTheImageUrl() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));

        extractor.extractReadingOrThrow(IMAGE_URL, null);

        // No consumerId: it would switch on the previous-reading checks, which block meter replacement.
        assertThat(restTemplate.lastPayload()).containsOnlyKeys("id", "imageURL");
        assertThat(restTemplate.lastPayload()).containsEntry("imageURL", IMAGE_URL);
        assertThat(restTemplate.lastPayload()).containsEntry("id",
                UUID.nameUUIDFromBytes(IMAGE_URL.getBytes(StandardCharsets.UTF_8)).toString());
    }

    @Test
    void resendsTheSameIdForTheSamePhotoSoARetryReplaysTheStoredRead() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));
        restTemplate.enqueue(ok(accepted("0000042.0", "kWh")));

        extractor.extractReadingOrThrow(IMAGE_URL, null);
        extractor.extractReadingOrThrow(IMAGE_URL, null);
        extractor.extractReadingOrThrow("https://images.example/photos/000124.jpg", null);

        List<Object> ids = restTemplate.payloads().stream().map(payload -> payload.get("id")).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(2)).isNotEqualTo(ids.get(0));
    }

    @Test
    void sendsTheGlobalKeyInTheXApiKeyHeaderToTheGlobalEndpoint() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));

        extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(restTemplate.lastUrl()).isEqualTo(ELM_URL);
        assertThat(restTemplate.lastHeaders().getFirst("X-API-Key")).isEqualTo(GLOBAL_API_KEY);
        assertThat(restTemplate.lastHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void usesTheTenantsEndpointKeyAndHeader() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));
        OcrProviderSettings settings =
                new OcrProviderSettings("flowvision-elm", "https://tenant-elm/extract", "tenant-key", "X-Tenant-Key");

        extractor.extractReadingOrThrow(IMAGE_URL, settings);

        assertThat(restTemplate.lastUrl()).isEqualTo("https://tenant-elm/extract");
        assertThat(restTemplate.lastHeaders().getFirst("X-Tenant-Key")).isEqualTo("tenant-key");
        assertThat(restTemplate.lastHeaders().getFirst("X-API-Key")).isNull();
    }

    @Test
    void neverSendsTheGlobalKeyToATenantEndpoint() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));
        OcrProviderSettings settings = new OcrProviderSettings("flowvision-elm", "https://tenant-elm/extract", null, null);

        extractor.extractReadingOrThrow(IMAGE_URL, settings);

        assertThat(restTemplate.lastUrl()).isEqualTo("https://tenant-elm/extract");
        assertThat(restTemplate.lastHeaders().getFirst("X-API-Key")).isNull();
    }

    @Test
    void fillsFieldsTheTenantLeftUnsetFromTheGlobalConfiguration() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));
        OcrProviderSettings settings = new OcrProviderSettings("flowvision-elm", null, null, null);

        extractor.extractReadingOrThrow(IMAGE_URL, settings);

        assertThat(restTemplate.lastUrl()).isEqualTo(ELM_URL);
        assertThat(restTemplate.lastHeaders().getFirst("X-API-Key")).isEqualTo(GLOBAL_API_KEY);
    }

    @Test
    void sendsNoKeyWhenNoneIsConfigured() {
        FlowVisionElmOcrExtractor withoutKey = new FlowVisionElmOcrExtractor(restTemplate, ELM_URL, "", "X-API-Key", "");
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));

        withoutKey.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(restTemplate.lastHeaders().getFirst("X-API-Key")).isNull();
    }

    @Test
    void refusesToCallWhenNoEndpointIsConfigured() {
        FlowVisionElmOcrExtractor unconfigured = new FlowVisionElmOcrExtractor(restTemplate, "", GLOBAL_API_KEY, "X-API-Key", "");

        assertThatThrownBy(() -> unconfigured.extractReadingOrThrow(IMAGE_URL, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(unconfigured.extractReading(IMAGE_URL, null)).isNull();
        assertThat(restTemplate.callCount()).isZero();
    }

    @Test
    void failsToStartAsElmsDefaultProviderWithNoEndpoint() {
        assertThatThrownBy(() -> new FlowVisionElmOcrExtractor(restTemplate, "", GLOBAL_API_KEY, "X-API-Key", " FlowVision-ELM "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCR_ELM_URL");
    }

    @Test
    void acceptsAKwhReadingAsDisplayed() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.getAdjustedReading()).isEqualByComparingTo("19712.7");
        assertThat(result.getRawMeterReading()).isEqualTo("0019712.7");
        assertThat(result.getQualityStatus()).isEqualTo("SUCCESS");
        assertThat(result.getCorrelationId()).isEqualTo("5f0c2a8e-1b7d-4c1e-9d3a-2f7e0b6a9c41");
        assertThat(result.getRequestId()).isEqualTo(restTemplate.lastPayload().get("id"));
        assertThat(result.getRejectionReason()).isNull();
        assertThat(result.isNoMeter()).isFalse();
        // An ELM display shows its own decimal point, and its register has no rollover digits.
        assertThat(result.isRedLastDigit()).isFalse();
        assertThat(result.isHasRollover()).isFalse();
        assertThat(result.getRolloverPositions()).isEmpty();
    }

    @Test
    void takesTheLowestConfidenceAcrossTheModelsReads() {
        Map<String, Object> body = accepted("0019712.7", "kWh");
        data(body).put("reads", List.of(
                Map.of("status", "ok", "confidence", 0.97),
                Map.of("status", "ok", "confidence", 0.91),
                Map.of("status", "ok")));
        restTemplate.enqueue(ok(body));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.getQualityConfidence()).isEqualByComparingTo(new BigDecimal("0.91"));
    }

    @Test
    void leavesConfidenceUnsetWhenNoReadCarriesOne() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kWh")));

        assertThat(extractor.extractReadingOrThrow(IMAGE_URL, null).getQualityConfidence()).isNull();
    }

    @Test
    void rejectsAKvahReadingBecauseTheElmFormulasExpectKwh() {
        restTemplate.enqueue(ok(accepted("0019712.7", "kVAh")));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.getAdjustedReading()).isNull();
        assertThat(result.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectionReason()).contains("kWh");
    }

    @Test
    void treatsAnAcceptedReadingThatIsNotANumberAsUnreadable() {
        restTemplate.enqueue(ok(accepted("00197?2.7", "kWh")));

        assertThat(extractor.extractReadingOrThrow(IMAGE_URL, null)).isNull();
    }

    @Test
    void flagsNoMeterAndIgnoresTheTextInPlaceOfTheReading() {
        Map<String, Object> body = envelope("NOMETER", "RETAKE", "No meter display is visible in the photo");
        data(body).put("retakeHint", "Take the photo facing the meter display");
        restTemplate.enqueue(ok(body));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.isNoMeter()).isTrue();
        assertThat(result.getAdjustedReading()).isNull();
        assertThat(result.getRawMeterReading()).isNull();
        assertThat(result.getQualityStatus()).isEqualTo("NOMETER");
        assertThat(result.getCorrelationId()).isEqualTo("5f0c2a8e-1b7d-4c1e-9d3a-2f7e0b6a9c41");
    }

    @Test
    void rejectsAnUnclearPhotoWithTheRetakeHint() {
        Map<String, Object> body = envelope("UNCLEAR", "RETAKE", "0019712.7");
        data(body).put("retakeHint", "Glare covers the last digit. Retake the photo without the flash.");
        restTemplate.enqueue(ok(body));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        // meterReading on UNCLEAR is only the model's best guess.
        assertThat(result.getAdjustedReading()).isNull();
        assertThat(result.isNoMeter()).isFalse();
        assertThat(result.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectionReason())
                .isEqualTo("Glare covers the last digit. Retake the photo without the flash.");
    }

    @Test
    void rejectsAReadingThatNeedsReviewWithoutShowingTheStaffReasons() {
        Map<String, Object> body = envelope("NEEDS_REVIEW", "REVIEW", "0019712.7");
        data(body).put("reasons", List.of(Map.of(
                "code", "DIGIT_COUNT_MISMATCH",
                "message", "Read 7 digits but meter type hpl_ppsm01 has 8")));
        restTemplate.enqueue(ok(body));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.getAdjustedReading()).isNull();
        assertThat(result.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectionReason()).isNotBlank().doesNotContain("hpl_ppsm01");
    }

    @Test
    void rejectsAClientErrorWithTheServicesMessage() {
        restTemplate.enqueue(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, errorBody(400, "ERROR_05", "The image URL returned HTTP 404", false), StandardCharsets.UTF_8));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.getAdjustedReading()).isNull();
        assertThat(result.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectionReason()).isEqualTo("The image URL returned HTTP 404");
    }

    @Test
    void rethrowsAnUnavailableServiceForTheResilienceLayer() {
        restTemplate.enqueue(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                HttpHeaders.EMPTY, errorBody(503, "ERROR_10", "The service is busy", true), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> extractor.extractReadingOrThrow(IMAGE_URL, null))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
    }

    @Test
    void rejectsAnUnavailableServiceOnTheNonResilientPath() {
        restTemplate.enqueue(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                HttpHeaders.EMPTY, errorBody(503, "ERROR_10", "The service is busy", true), StandardCharsets.UTF_8));

        OcrReadingResult result = extractor.extractReading(IMAGE_URL, null);

        assertThat(result.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectionReason()).isEqualTo("The service is busy");
    }

    @Test
    void rethrowsAnUnavailableServiceReportedInsideAnOkResponse() {
        restTemplate.enqueue(ok(errorEnvelope(503, "ERROR_13", "The database is unavailable", true)));

        assertThatThrownBy(() -> extractor.extractReadingOrThrow(IMAGE_URL, null))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class)
                .satisfies(ex -> assertThat(OcrTransientFailures.isServiceUnavailable(ex)).isTrue());
    }

    @Test
    void rethrowsARateLimitReportedInsideAnOkResponse() {
        restTemplate.enqueue(ok(errorEnvelope(429, "ERROR_11", "Retry in a minute", true)));

        assertThatThrownBy(() -> extractor.extractReadingOrThrow(IMAGE_URL, null))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);
    }

    @Test
    void rejectsAClientErrorReportedInsideAnOkResponse() {
        restTemplate.enqueue(ok(errorEnvelope(400, "ERROR_05", "The image URL returned HTTP 404", false)));

        OcrReadingResult result = extractor.extractReadingOrThrow(IMAGE_URL, null);

        assertThat(result.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectionReason()).isEqualTo("The image URL returned HTTP 404");
    }

    @Test
    void returnsNullForAnErrorWithoutTheServicesEnvelope() {
        restTemplate.enqueue(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                HttpHeaders.EMPTY, "<html>oops</html>".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThat(extractor.extractReadingOrThrow(IMAGE_URL, null)).isNull();
    }

    @Test
    void returnsNullForAResponseWithNeitherResultNorError() {
        restTemplate.enqueue(ok(new HashMap<>(Map.of("id", "x", "responseCode", "OK", "statusCode", 200))));

        assertThat(extractor.extractReadingOrThrow(IMAGE_URL, null)).isNull();
    }

    @Test
    void letsAnIoFailurePropagateOnTheResilientPathAndAbsorbsItOtherwise() {
        restTemplate.enqueue(new ResourceAccessException("Read timed out"));
        restTemplate.enqueue(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> extractor.extractReadingOrThrow(IMAGE_URL, null))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(extractor.extractReading(IMAGE_URL, null)).isNull();
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    /** The service's ACCEPT response, shaped as in its integration guide. */
    private static Map<String, Object> accepted(String meterReading, String unit) {
        Map<String, Object> body = envelope("SUCCESS", "ACCEPT", meterReading);
        data(body).put("unit", unit);
        return body;
    }

    private static Map<String, Object> envelope(String status, String action, String meterReading) {
        Map<String, Object> data = new HashMap<>();
        data.put("meterReading", meterReading);
        data.put("action", action);
        data.put("uncertainDigits", List.of());
        data.put("reasons", List.of());
        data.put("processingTime", 19.4);
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("correlationId", "5f0c2a8e-1b7d-4c1e-9d3a-2f7e0b6a9c41");
        result.put("data", data);
        Map<String, Object> body = new HashMap<>();
        body.put("ts", "2026-09-30T04:45:21.512Z");
        body.put("responseCode", "OK");
        body.put("statusCode", 200);
        body.put("result", result);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> body) {
        return (Map<String, Object>) ((Map<String, Object>) body.get("result")).get("data");
    }

    private static Map<String, Object> errorEnvelope(int statusCode, String errorCode, String errorMsg, boolean retryable) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", "4c3a2b1d-0000-3000-8000-000000000000");
        body.put("ts", "2026-09-30T04:45:21.512Z");
        body.put("responseCode", "ERROR");
        body.put("statusCode", statusCode);
        body.put("error", Map.of("errorCode", errorCode, "errorMsg", errorMsg, "retryable", retryable));
        return body;
    }

    private static byte[] errorBody(int statusCode, String errorCode, String errorMsg, boolean retryable) {
        return ("""
                {"id": "4c3a2b1d-0000-3000-8000-000000000000", "ts": "2026-09-30T04:45:21.512Z",
                 "responseCode": "ERROR", "statusCode": %d,
                 "error": {"errorCode": "%s", "errorMsg": "%s", "retryable": %b}}
                """.formatted(statusCode, errorCode, errorMsg, retryable)).getBytes(StandardCharsets.UTF_8);
    }

    /** Plays back queued responses or exceptions, recording each request it was sent. */
    private static final class ScriptedRestTemplate extends RestTemplate {
        private final Deque<Object> scriptedResponses = new ArrayDeque<>();
        private final List<Map<String, Object>> payloads = new ArrayList<>();
        private String lastUrl;
        private HttpHeaders lastHeaders;

        void enqueue(Object responseOrException) {
            scriptedResponses.addLast(responseOrException);
        }

        int callCount() {
            return payloads.size();
        }

        List<Map<String, Object>> payloads() {
            return payloads;
        }

        Map<String, Object> lastPayload() {
            return payloads.get(payloads.size() - 1);
        }

        String lastUrl() {
            return lastUrl;
        }

        HttpHeaders lastHeaders() {
            return lastHeaders;
        }

        @Override
        public <T> ResponseEntity<T> exchange(URI url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType) {
            throw new UnsupportedOperationException("URI-based overload not used");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity,
                                              Class<T> responseType, Object... uriVariables) {
            lastUrl = url;
            lastHeaders = requestEntity.getHeaders();
            payloads.add((Map<String, Object>) requestEntity.getBody());
            Object next = scriptedResponses.removeFirst();
            if (next instanceof RestClientException exception) {
                throw exception;
            }
            return (ResponseEntity<T>) next;
        }
    }
}
