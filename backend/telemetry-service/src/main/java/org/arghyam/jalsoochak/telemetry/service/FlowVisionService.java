package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.dto.response.RolloverPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class FlowVisionService implements MeterReadingExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    /** Global-default settings used when a caller supplies none (the legacy single-endpoint path). */
    private final OcrProviderSettings defaultSettings;

    @Autowired
    public FlowVisionService(
            RestTemplate restTemplate,
            @Value("${flowvision.url}") String flowVisionUrl,
            @Value("${flowvision.api-key:}") String apiKey,
            @Value("${flowvision.auth-header:" + OcrProviderSettings.DEFAULT_AUTH_HEADER + "}") String authHeader
    ) {
        this.restTemplate = restTemplate;
        this.defaultSettings = new OcrProviderSettings(
                OcrProviderSettings.DEFAULT_PROVIDER_ID,
                flowVisionUrl,
                (apiKey == null || apiKey.isBlank()) ? null : apiKey,
                authHeader);
    }

    /** Convenience constructor (no auth) retained for unit tests that stub the endpoint directly. */
    public FlowVisionService(RestTemplate restTemplate, String flowVisionUrl) {
        this(restTemplate, flowVisionUrl, null, OcrProviderSettings.DEFAULT_AUTH_HEADER);
    }

    @Override
    public String providerId() {
        return OcrProviderSettings.DEFAULT_PROVIDER_ID;
    }

    /** Extracts a reading against the global-default FlowVision endpoint (backwards-compatible entry point). */
    public FlowVisionResult extractReading(String readingUrl) {
        return extractReading(readingUrl, defaultSettings);
    }

    /** Throwing variant against the global-default endpoint (backwards-compatible entry point). */
    public FlowVisionResult extractReadingOrThrow(String readingUrl) {
        return extractReadingOrThrow(readingUrl, defaultSettings);
    }

    @Override
    public FlowVisionResult extractReading(String readingUrl, OcrProviderSettings settings) {
        OcrProviderSettings effective = settings == null ? defaultSettings : settings;
        String requestId = UUID.randomUUID().toString();

        try {
            return requestFlowVisionReading(readingUrl, requestId, effective);
        } catch (RestClientResponseException ex) {
            return handleFlowVisionErrorResponse(ex, readingUrl, requestId);
        } catch (Exception ex) {
            log.error("FlowVision OCR call failed for imageUrlHash={}: {}", imageUrlHash(readingUrl), ex.getMessage(), ex);
            if (log.isDebugEnabled()) {
                log.debug("FlowVision OCR call failed for image {}", readingUrl);
            }
            return null;
        }
    }

    @Override
    public FlowVisionResult extractReadingOrThrow(String readingUrl, OcrProviderSettings settings) {
        OcrProviderSettings effective = settings == null ? defaultSettings : settings;
        String requestId = UUID.randomUUID().toString();
        try {
            return requestFlowVisionReading(readingUrl, requestId, effective);
        } catch (RestClientResponseException ex) {
            if (FlowVisionTransientFailures.isServiceUnavailable(ex)) {
                // Let the retry mechanism handle transient FlowVision failures.
                throw ex;
            }
            return handleFlowVisionErrorResponse(ex, readingUrl, requestId);
        }
        // Transient failures such as ResourceAccessException propagate for retry.
    }

    private FlowVisionResult requestFlowVisionReading(String readingUrl, String requestId, OcrProviderSettings settings) {
        Map<String, String> payload = new HashMap<>();
        payload.put("id", requestId);
        payload.put("imageURL", readingUrl);

        String endpoint = firstNonBlank(settings.endpointUrl(), defaultSettings.endpointUrl());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (settings.hasApiKey()) {
            headers.set(settings.resolvedAuthHeaderName(), settings.apiKey());
        }

        HttpEntity<Map<String, String>> requestEntity =
                new HttpEntity<>(payload, headers);

        log.info("flowvision request imageUrlHash={} provider={} endpoint={}",
                imageUrlHash(readingUrl), settings.providerId(), endpoint);
        ResponseEntity<Map> responseEntity = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                requestEntity,
                Map.class
        );
        log.info("flowvision response status={} body={}",
                responseEntity.getStatusCode(),
                responseEntity.getBody());


        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            log.error("FlowVision HTTP error: {}", responseEntity.getStatusCode());
            return rejectedFlowVisionResult(responseEntity.getBody(), requestId);
        }

        Map<String, Object> responseBody = responseEntity.getBody();

        if (responseBody == null || !responseBody.containsKey("result")) {
            String errorMsg = extractFlowVisionErrorMsg(responseBody);
            if (errorMsg != null) {
                return rejectedFlowVisionResult(responseBody, requestId);
            }
            log.error("FlowVision response missing 'result'");
            return null;
        }

        Map<String, Object> resultMap =
                (Map<String, Object>) responseBody.get("result");

        if (resultMap == null || !"SUCCESS".equals(resultMap.get("status"))) {
            log.warn("FlowVision OCR not successful: {}", resultMap);
            String responseRequestId = extractRequestId(responseBody, resultMap, requestId);
            return FlowVisionResult.builder()
                    .rejectionReason(extractRejectionReason(resultMap))
                    .requestId(responseRequestId)
                    .correlationId(extractCorrelationId(resultMap))
                    .qualityStatus("REJECTED")
                    .build();
        }

        if (!resultMap.containsKey("data")) {
            log.error("FlowVision result missing 'data'");
            return null;
        }

        Map<String, Object> dataMap =
                (Map<String, Object>) resultMap.get("data");

        BigDecimal adjustedReading = parseMeterReading(dataMap);

        String qualityStatus =
                dataMap.getOrDefault("qualityStatus", "unknown").toString();

        BigDecimal qualityConfidence = null;
        Object confidenceObj = dataMap.get("qualityConfidence");

        if (confidenceObj != null) {
            qualityConfidence = new BigDecimal(confidenceObj.toString());
        }

        String correlationId =
                resultMap.getOrDefault(
                        "correlationId",
                        UUID.randomUUID().toString()
                ).toString();
        String responseRequestId = extractRequestId(responseBody, resultMap, requestId);

        List<RolloverPosition> rolloverPositions = parseRolloverPositions(dataMap);
        FlowVisionResult result = FlowVisionResult.builder()
                .adjustedReading(adjustedReading)
                .rawMeterReading(extractRawMeterReading(dataMap))
                .redLastDigit(isRedLastDigit(dataMap))
                .hasRollover(isRolloverPresent(dataMap, rolloverPositions))
                .rolloverPositions(rolloverPositions)
                .requestId(responseRequestId)
                .qualityStatus(qualityStatus)
                .qualityConfidence(qualityConfidence)
                .correlationId(correlationId)
                .rejectionReason(extractExplicitRejectionReason(dataMap))
                .build();
        log.info("flowvision parsed_result imageUrlHash={} result={}",
                imageUrlHash(readingUrl),
                summarizeFlowVisionResult(result));
        return result;
    }

    private FlowVisionResult handleFlowVisionErrorResponse(RestClientResponseException ex, String readingUrl, String requestId) {
        String errorMsg = extractFlowVisionErrorMsg(ex.getResponseBodyAsString());
        if (errorMsg != null) {
            log.warn("FlowVision OCR rejected imageUrlHash={} status={} errorMsg={}",
                    imageUrlHash(readingUrl),
                    ex.getStatusCode(),
                    sanitizeLogValue(errorMsg));
            return FlowVisionResult.builder()
                    .rejectionReason(errorMsg)
                    .requestId(requestId)
                    .qualityStatus("REJECTED")
                    .correlationId(UUID.randomUUID().toString())
                    .build();
        }
        log.error("FlowVision OCR HTTP error for imageUrlHash={}: {}", imageUrlHash(readingUrl), ex.getMessage(), ex);
        return null;
    }

    private BigDecimal parseMeterReading(Map<String, Object> dataMap) {
        Object meterReadingObj = dataMap.get("meterReading");
        if (meterReadingObj == null) {
            return null;
        }

        String meterReading = meterReadingObj.toString().trim();
        if (meterReading.isEmpty()) {
            return null;
        }

        BigDecimal parsedReading = new BigDecimal(meterReading);
        if (!isRedColor(dataMap.get("lastDigitColor"))) {
            return parsedReading;
        }

        return parsedReading.movePointLeft(1).setScale(1, RoundingMode.UNNECESSARY);
    }

    /**
     * Whether {@code lastDigitColor} normalises to {@code "red"} — the single source of truth shared by
     * {@link #parseMeterReading} (decimal-shift decision) and {@link #isRedLastDigit} (metadata), so the
     * persisted {@code redLastDigit} flag can never disagree with the shift applied to {@code adjustedReading}.
     */
    private static boolean isRedColor(Object lastDigitColorObj) {
        if (lastDigitColorObj == null) {
            return false;
        }
        return "red".equals(lastDigitColorObj.toString().trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The raw {@code data.meterReading} string exactly as FlowVision returned it (built from its
     * {@code selectedDigit}s), before any red-last-digit decimal shift. Preserves digit count and
     * leading zeros so rollover candidate enumeration can reason positionally. {@code null} when absent
     * or blank.
     */
    private String extractRawMeterReading(Map<String, Object> dataMap) {
        Object meterReadingObj = dataMap.get("meterReading");
        if (meterReadingObj == null) {
            return null;
        }
        String meterReading = meterReadingObj.toString().trim();
        return meterReading.isEmpty() ? null : meterReading;
    }

    private boolean isRedLastDigit(Map<String, Object> dataMap) {
        return isRedColor(dataMap.get("lastDigitColor"));
    }

    /**
     * {@code true} when the payload signals a rollover. Tolerates old payloads: absence of both the
     * {@code hasRollover} flag and any parsed positions yields {@code false}. A truthy {@code hasRollover}
     * flag OR a non-empty parsed positions list is treated as a rollover.
     */
    private boolean isRolloverPresent(Map<String, Object> dataMap, List<RolloverPosition> positions) {
        Object hasRollover = dataMap.get("hasRollover");
        boolean flagged = hasRollover != null && Boolean.parseBoolean(hasRollover.toString().trim());
        return flagged || !positions.isEmpty();
    }

    /**
     * Parses {@code data.rolloverPositions[]} into typed records. Tolerates absence (returns empty list)
     * and skips malformed entries so a partial payload never breaks OCR ingestion. Digit/confidence keys
     * accept the {@code *Value}/{@code *Digit} aliases FlowVision may use.
     */
    private List<RolloverPosition> parseRolloverPositions(Map<String, Object> dataMap) {
        Object raw = dataMap.get("rolloverPositions");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<RolloverPosition> positions = new ArrayList<>(rawList.size());
        for (Object element : rawList) {
            if (!(element instanceof Map<?, ?> entry)) {
                continue;
            }
            Integer position = intOrNull(firstPresent(entry, "position"));
            // FlowVision may emit each digit either flat (selectedValue: 1, selectedConfidence: 0.55) or
            // nested (selectedDigit: {value: 5, confidence: 0.94}); tolerate both so the resolver is not a
            // silent no-op if prod uses the nested shape. The digit alias also carries the nested object,
            // so unwrap its value/confidence when present, falling back to the flat sibling keys otherwise.
            Object selectedNode = firstPresent(entry, "selectedValue", "selectedDigit");
            Object alternateNode = firstPresent(entry, "alternateValue", "alternateDigit");
            Integer selectedValue = intOrNull(digitValue(selectedNode));
            Integer alternateValue = intOrNull(digitValue(alternateNode));
            // A rollover digit must be a single decimal digit; a multi-digit / out-of-range value would
            // corrupt positional candidate enumeration (it maps one raw-string position to one char).
            if (position == null || !isDecimalDigit(selectedValue) || !isDecimalDigit(alternateValue)) {
                log.warn("Skipping malformed FlowVision rolloverPosition entry: {}", sanitizeLogValue(String.valueOf(entry)));
                continue;
            }
            positions.add(new RolloverPosition(
                    position,
                    selectedValue,
                    bigDecimalOrNull(firstNonNull(digitConfidence(selectedNode), firstPresent(entry, "selectedConfidence"))),
                    alternateValue,
                    bigDecimalOrNull(firstNonNull(digitConfidence(alternateNode), firstPresent(entry, "alternateConfidence")))
            ));
        }
        return positions.isEmpty() ? List.of() : List.copyOf(positions);
    }

    private Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Unwraps a digit node: a nested {@code {value, confidence}} object yields its {@code value}; a scalar yields itself. */
    private static Object digitValue(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map.get("value");
        }
        return node;
    }

    /** Confidence carried inside a nested digit node ({@code {value, confidence}}), or {@code null} for a scalar. */
    private static Object digitConfidence(Object node) {
        if (node instanceof Map<?, ?> map) {
            return map.get("confidence");
        }
        return null;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static boolean isDecimalDigit(Integer value) {
        return value != null && value >= 0 && value <= 9;
    }

    private Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            // Reject fractional metadata (e.g. selectedDigit 1.9, position 2.7) rather than truncating it:
            // intValueExact() throws on any non-integral value, so only exact integers reach isDecimalDigit.
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).intValueExact();
            }
            return new BigDecimal(value.toString().trim()).intValueExact();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private BigDecimal bigDecimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String summarizeFlowVisionResult(FlowVisionResult result) {
        if (result == null) {
            return "null";
        }
        return String.format(
                "{adjustedReading=%s,qualityStatus=%s,qualityConfidence=%s,correlationId=%s}",
                result.getAdjustedReading(),
                sanitizeLogValue(result.getQualityStatus()),
                result.getQualityConfidence(),
                sanitizeLogValue(result.getCorrelationId())
        );
    }

    private String extractCorrelationId(Map<String, Object> resultMap) {
        if (resultMap == null) {
            return UUID.randomUUID().toString();
        }
        return resultMap.getOrDefault("correlationId", UUID.randomUUID().toString()).toString();
    }

    private String extractRequestId(Map<String, Object> responseBody, Map<String, Object> resultMap, String generatedRequestId) {
        String responseRequestId = firstNonBlank(
                valueAsString(resultMap, "id"),
                valueAsString(responseBody, "id"),
                generatedRequestId
        );
        if (!generatedRequestId.equals(responseRequestId)) {
            log.warn("FlowVision echoed id differs from generated request id generatedId={} responseId={}",
                    sanitizeLogValue(generatedRequestId),
                    sanitizeLogValue(responseRequestId));
        }
        return responseRequestId;
    }

    private String valueAsString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String extractRejectionReason(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        for (String key : new String[]{"rejectionReason", "reason", "message", "error"}) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        Object data = map.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            for (String key : new String[]{"rejectionReason", "reason", "message", "error"}) {
                Object value = dataMap.get(key);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString();
                }
            }
        }
        return null;
    }

    private FlowVisionResult rejectedFlowVisionResult(Map<String, Object> responseBody, String requestId) {
        String errorMsg = extractFlowVisionErrorMsg(responseBody);
        return FlowVisionResult.builder()
                .rejectionReason(errorMsg)
                .requestId(extractRequestId(responseBody, null, requestId))
                .qualityStatus("REJECTED")
                .correlationId(extractCorrelationId(responseBody))
                .build();
    }

    private String extractFlowVisionErrorMsg(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(responseBody, new TypeReference<>() {
            });
            return extractFlowVisionErrorMsg(parsed);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractFlowVisionErrorMsg(Map<String, Object> responseBody) {
        if (responseBody == null) {
            return null;
        }
        Object error = responseBody.get("error");
        if (error instanceof Map<?, ?> errorMap) {
            Object errorMsg = errorMap.get("errorMsg");
            if (errorMsg != null && !errorMsg.toString().isBlank()) {
                return errorMsg.toString();
            }
        }
        return null;
    }

    private String extractExplicitRejectionReason(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        for (String key : new String[]{"rejectionReason", "reason", "message", "error"}) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
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
