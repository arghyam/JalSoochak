package org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingExtractor;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrTransientFailures;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The OCR provider for electricity meters. It reads the cumulative kWh register from an ELM photo.
 *
 * <p>A read takes 20–30 seconds, so it goes out on its own {@code elmOcrRestTemplate} with a read
 * timeout sized for it ({@code ocr.elm.http.read-timeout-ms}). The request id is derived from the image
 * URL: the service treats the id as an idempotency key, so a retry of the same photo gets the stored
 * result back instead of a second paid read. No {@code consumerId} is sent. It would switch on the
 * service's previous-reading checks, and those would reject the first reading of a replaced meter.
 *
 * <p>As ELM's default provider it reads for every tenant that names no endpoint of its own, so it
 * fails startup without {@code ocr.elm.url}. Otherwise an unconfigured deployment would reject every
 * such ELM photo.
 *
 * <p>Only an {@code ACCEPT} in kWh becomes a reading. {@code NOMETER} is a photo without a meter; every
 * other verdict, a kVAh reading included, is a rejection.
 */
@Component
@Slf4j
public class FlowVisionElmOcrExtractor implements MeterReadingExtractor {

    static final String PROVIDER_ID = "flowvision-elm";
    static final String DEFAULT_AUTH_HEADER = "X-API-Key";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_NO_METER = "NOMETER";
    private static final String STATUS_UNCLEAR = "UNCLEAR";
    private static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final String QUALITY_REJECTED = "REJECTED";
    private static final String RESPONSE_CODE_ERROR = "ERROR";
    private static final String BILLING_UNIT = "kWh";
    private static final String NOT_KWH_MESSAGE = "Please send a photo of the meter showing its kWh reading.";
    /** {@code reasons[]} on a NEEDS_REVIEW result are written for staff, so the submitter gets this instead. */
    private static final String NEEDS_REVIEW_MESSAGE =
            "The reading could not be confirmed. Please send another clear photo of the meter.";

    private final RestTemplate restTemplate;
    private final String defaultEndpointUrl;
    private final String defaultApiKey;
    private final String defaultAuthHeader;

    public FlowVisionElmOcrExtractor(
            @Qualifier("elmOcrRestTemplate") RestTemplate restTemplate,
            @Value("${ocr.elm.url:}") String endpointUrl,
            @Value("${ocr.elm.api-key:}") String apiKey,
            @Value("${ocr.elm.auth-header:" + DEFAULT_AUTH_HEADER + "}") String authHeader,
            @Value("${ocr.elm.default-provider:}") String elmDefaultProviderId
    ) {
        this.restTemplate = restTemplate;
        this.defaultEndpointUrl = blankToNull(endpointUrl);
        this.defaultApiKey = blankToNull(apiKey);
        this.defaultAuthHeader = Objects.requireNonNullElse(blankToNull(authHeader), DEFAULT_AUTH_HEADER);
        if (defaultEndpointUrl == null && elmDefaultProviderId != null
                && PROVIDER_ID.equalsIgnoreCase(elmDefaultProviderId.trim())) {
            throw new IllegalStateException("ocr.elm.default-provider is '" + PROVIDER_ID
                    + "' but ocr.elm.url is blank: set OCR_ELM_URL, or set OCR_ELM_DEFAULT_PROVIDER blank"
                    + " to make ELM OCR opt-in per tenant");
        }
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.ELM;
    }

    @Override
    public OcrReadingResult extractReading(String imageUrl, OcrProviderSettings settings) {
        String requestId = requestId(imageUrl);
        try {
            return requestReading(imageUrl, requestId, settings);
        } catch (RestClientResponseException ex) {
            return handleErrorResponse(ex, imageUrl, requestId);
        } catch (Exception ex) {
            log.error("ELM OCR call failed for imageUrlHash={}: {}", imageUrlHash(imageUrl), ex.getMessage(), ex);
            return null;
        }
    }

    @Override
    public OcrReadingResult extractReadingOrThrow(String imageUrl, OcrProviderSettings settings) {
        String requestId = requestId(imageUrl);
        try {
            return requestReading(imageUrl, requestId, settings);
        } catch (RestClientResponseException ex) {
            if (OcrTransientFailures.isServiceUnavailable(ex)) {
                throw ex;
            }
            return handleErrorResponse(ex, imageUrl, requestId);
        }
        // I/O failures such as ResourceAccessException propagate for retry.
    }

    /** The same photo always gets the same id, so the service replays a retried read rather than redoing it. */
    private static String requestId(String imageUrl) {
        String url = imageUrl == null ? "" : imageUrl;
        return UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private OcrReadingResult requestReading(String imageUrl, String requestId, OcrProviderSettings settings) {
        String endpoint = endpoint(settings);
        if (endpoint == null) {
            throw new IllegalStateException(
                    "No ELM OCR endpoint configured: set ocr.elm.url or the tenant's ocr_elm_url");
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("id", requestId);
        payload.put("imageURL", imageUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKey = apiKey(settings, endpoint);
        if (apiKey != null) {
            headers.set(authHeader(settings), apiKey);
        }

        log.info("ELM OCR request imageUrlHash={} requestId={} endpoint={}", imageUrlHash(imageUrl), requestId, endpoint);
        ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        if (body != null && RESPONSE_CODE_ERROR.equals(body.get("responseCode"))) {
            // The service can be switched to answer every error with HTTP 200 and the real status in the
            // body. Raising the error that status stands for handles both styles the same way.
            throw asHttpError(body);
        }
        return toResult(body, imageUrl, requestId);
    }

    private OcrReadingResult toResult(Map<String, Object> body, String imageUrl, String requestId) {
        Map<String, Object> result = body == null ? null : asMap(body.get("result"));
        if (body == null || result == null) {
            log.error("ELM OCR response has no result imageUrlHash={}", imageUrlHash(imageUrl));
            return null;
        }
        String status = text(result.get("status"));
        Map<String, Object> data = Objects.requireNonNullElseGet(asMap(result.get("data")), Map::of);
        String responseRequestId = Objects.requireNonNullElse(text(body.get("id")), requestId);
        String correlationId = Objects.requireNonNullElseGet(text(result.get("correlationId")), () -> UUID.randomUUID().toString());
        String normalizedStatus = status == null ? "" : status.toUpperCase(Locale.ROOT);

        return switch (normalizedStatus) {
            case STATUS_SUCCESS -> accepted(data, imageUrl, responseRequestId, correlationId);
            case STATUS_NO_METER -> {
                log.warn("ELM OCR found no meter imageUrlHash={} correlationId={}", imageUrlHash(imageUrl), correlationId);
                yield OcrReadingResult.builder()
                        .noMeter(true)
                        .qualityStatus(STATUS_NO_METER)
                        .rejectionReason(text(data.get("retakeHint")))
                        .requestId(responseRequestId)
                        .correlationId(correlationId)
                        .build();
            }
            case STATUS_UNCLEAR -> rejected(text(data.get("retakeHint")), status, data, imageUrl, responseRequestId, correlationId);
            case STATUS_NEEDS_REVIEW -> rejected(NEEDS_REVIEW_MESSAGE, status, data, imageUrl, responseRequestId, correlationId);
            default -> rejected(null, status, data, imageUrl, responseRequestId, correlationId);
        };
    }

    private OcrReadingResult accepted(Map<String, Object> data, String imageUrl, String requestId, String correlationId) {
        String meterReading = text(data.get("meterReading"));
        if (meterReading == null) {
            log.error("ELM OCR accepted a reading without meterReading imageUrlHash={}", imageUrlHash(imageUrl));
            return null;
        }
        String unit = text(data.get("unit"));
        if (!BILLING_UNIT.equalsIgnoreCase(unit)) {
            return rejected(NOT_KWH_MESSAGE, STATUS_SUCCESS + "/" + unit, data, imageUrl, requestId, correlationId);
        }
        BigDecimal reading;
        try {
            reading = new BigDecimal(meterReading);
        } catch (NumberFormatException ex) {
            log.error("ELM OCR accepted a reading that is not a number imageUrlHash={} correlationId={}",
                    imageUrlHash(imageUrl), correlationId);
            return null;
        }
        OcrReadingResult result = OcrReadingResult.builder()
                .adjustedReading(reading)
                .rawMeterReading(meterReading)
                .qualityStatus(STATUS_SUCCESS)
                .qualityConfidence(lowestConfidence(data.get("reads")))
                .requestId(requestId)
                .correlationId(correlationId)
                .build();
        log.info("ELM OCR accepted imageUrlHash={} correlationId={} reading={} confidence={}",
                imageUrlHash(imageUrl), correlationId, reading, result.getQualityConfidence());
        return result;
    }

    /** The weakest of the model's reads, so a reading is never reported as surer than its least sure read. */
    private static BigDecimal lowestConfidence(Object reads) {
        if (!(reads instanceof List<?> list)) {
            return null;
        }
        BigDecimal lowest = null;
        for (Object read : list) {
            Object confidence = read instanceof Map<?, ?> map ? map.get("confidence") : null;
            if (confidence == null) {
                continue;
            }
            try {
                BigDecimal value = new BigDecimal(confidence.toString());
                lowest = lowest == null || value.compareTo(lowest) < 0 ? value : lowest;
            } catch (NumberFormatException ex) {
                log.warn("Ignoring an ELM OCR read with a non-numeric confidence");
            }
        }
        return lowest;
    }

    private OcrReadingResult rejected(String reason, String status, Map<String, Object> data, String imageUrl,
                                      String requestId, String correlationId) {
        log.warn("ELM OCR rejected imageUrlHash={} status={} reasonCodes={} correlationId={}",
                imageUrlHash(imageUrl), status, reasonCodes(data), correlationId);
        return OcrReadingResult.builder()
                .rejectionReason(reason)
                .qualityStatus(QUALITY_REJECTED)
                .requestId(requestId)
                .correlationId(correlationId)
                .build();
    }

    private static List<String> reasonCodes(Map<String, Object> data) {
        Object reasons = data.get("reasons");
        if (!(reasons instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(reason -> reason instanceof Map<?, ?> map ? text(map.get("code")) : null)
                .filter(Objects::nonNull)
                .toList();
    }

    private OcrReadingResult handleErrorResponse(RestClientResponseException ex, String imageUrl, String requestId) {
        String errorMsg = errorMsg(parse(ex.getResponseBodyAsString()));
        if (errorMsg == null) {
            log.error("ELM OCR HTTP error for imageUrlHash={}: {}", imageUrlHash(imageUrl), ex.getMessage(), ex);
            return null;
        }
        log.warn("ELM OCR error imageUrlHash={} status={} errorMsg={}",
                imageUrlHash(imageUrl), ex.getStatusCode(), errorMsg.replace('\n', ' ').replace('\r', ' '));
        return OcrReadingResult.builder()
                .rejectionReason(errorMsg)
                .qualityStatus(QUALITY_REJECTED)
                .requestId(requestId)
                .correlationId(UUID.randomUUID().toString())
                .build();
    }

    /** The HTTP error an error envelope's {@code statusCode} stands for; 500 when it carries none. */
    private static HttpStatusCodeException asHttpError(Map<String, Object> body) {
        Object statusCode = body.get("statusCode");
        int code = statusCode instanceof Number number ? number.intValue() : 500;
        HttpStatusCode status = HttpStatusCode.valueOf(code >= 400 && code <= 599 ? code : 500);
        byte[] responseBody;
        try {
            responseBody = OBJECT_MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            responseBody = new byte[0];
        }
        return status.is4xxClientError()
                ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, responseBody, StandardCharsets.UTF_8)
                : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, responseBody, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(body, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String errorMsg(Map<String, Object> body) {
        Map<String, Object> error = body == null ? null : asMap(body.get("error"));
        return error == null ? null : text(error.get("errorMsg"));
    }

    private String endpoint(OcrProviderSettings settings) {
        String tenantUrl = settings == null ? null : blankToNull(settings.endpointUrl());
        return tenantUrl != null ? tenantUrl : defaultEndpointUrl;
    }

    /** The configured key is sent only to the configured endpoint, never to one a tenant points at. */
    private String apiKey(OcrProviderSettings settings, String endpoint) {
        if (settings != null && settings.hasApiKey()) {
            return settings.apiKey();
        }
        return endpoint.equals(defaultEndpointUrl) ? defaultApiKey : null;
    }

    private String authHeader(OcrProviderSettings settings) {
        String tenantHeader = settings == null ? null : blankToNull(settings.authHeaderName());
        return tenantHeader != null ? tenantHeader : defaultAuthHeader;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static String text(Object value) {
        return value == null ? null : blankToNull(value.toString().trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String imageUrlHash(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return "n/a";
        }
        return Integer.toHexString(imageUrl.hashCode());
    }
}
