package org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.dto.response.RolloverPosition;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingExtractor;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrTransientFailures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The built-in OCR provider for bulk flow meters. It is BFM's default provider
 * ({@code ocr.default-provider}), so it serves every tenant that sets no {@code ocr_*} override.
 */
@Component
@Slf4j
public class FlowVisionBfmOcrExtractor implements MeterReadingExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** Status FlowVision returns when the photo holds no meter at all (rotated, unrelated, random). */
    static final String NO_METER_STATUS = "NOMETER";

    private final RestTemplate restTemplate;
    /** Global-default settings used when a caller supplies none (the legacy single-endpoint path). */
    private final OcrProviderSettings defaultSettings;

    @Autowired
    public FlowVisionBfmOcrExtractor(
            RestTemplate restTemplate,
            @Value("${ocr.url}") String flowVisionUrl,
            @Value("${ocr.api-key:}") String apiKey,
            @Value("${ocr.auth-header:" + OcrProviderSettings.DEFAULT_AUTH_HEADER + "}") String authHeader
    ) {
        this.restTemplate = restTemplate;
        this.defaultSettings = new OcrProviderSettings(
                OcrProviderSettings.DEFAULT_PROVIDER_ID,
                flowVisionUrl,
                (apiKey == null || apiKey.isBlank()) ? null : apiKey,
                authHeader);
    }

    /** Convenience constructor (no auth) retained for unit tests that stub the endpoint directly. */
    public FlowVisionBfmOcrExtractor(RestTemplate restTemplate, String flowVisionUrl) {
        this(restTemplate, flowVisionUrl, null, OcrProviderSettings.DEFAULT_AUTH_HEADER);
    }

    @Override
    public String providerId() {
        return OcrProviderSettings.DEFAULT_PROVIDER_ID;
    }

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.BFM;
    }

    @Override
    public OcrReadingResult extractReading(String readingUrl, OcrProviderSettings settings) {
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
    public OcrReadingResult extractReadingOrThrow(String readingUrl, OcrProviderSettings settings) {
        OcrProviderSettings effective = settings == null ? defaultSettings : settings;
        String requestId = UUID.randomUUID().toString();
        try {
            return requestFlowVisionReading(readingUrl, requestId, effective);
        } catch (RestClientResponseException ex) {
            if (OcrTransientFailures.isServiceUnavailable(ex)) {
                // Let the retry mechanism handle transient FlowVision failures.
                throw ex;
            }
            return handleFlowVisionErrorResponse(ex, readingUrl, requestId);
        }
        // Transient failures such as ResourceAccessException propagate for retry.
    }

    private OcrReadingResult requestFlowVisionReading(String readingUrl, String requestId, OcrProviderSettings settings) {
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

        // Checked ahead of the SUCCESS gate. A NOMETER response still carries a data block, but its
        // meterReading is free text ("No digits detected in the image") and its qualityStatus says
        // nothing about the meter, so none of it may be parsed as a reading.
        if (isNoMeter(resultMap)) {
            log.warn("FlowVision found no meter imageUrlHash={} correlationId={}",
                    imageUrlHash(readingUrl), sanitizeLogValue(extractCorrelationId(resultMap)));
            return OcrReadingResult.builder()
                    .noMeter(true)
                    .qualityStatus(NO_METER_STATUS)
                    .rejectionReason(extractRejectionReason(resultMap))
                    .requestId(extractRequestId(responseBody, resultMap, requestId))
                    .correlationId(extractCorrelationId(resultMap))
                    .build();
        }

        if (resultMap == null || !"SUCCESS".equals(resultMap.get("status"))) {
            log.warn("FlowVision OCR not successful: {}", resultMap);
            String responseRequestId = extractRequestId(responseBody, resultMap, requestId);
            return OcrReadingResult.builder()
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

        String meterReading = pointOldFormatRedReading(extractMeterReading(dataMap), dataMap.get("lastDigitColor"));
        BigDecimal adjustedReading = meterReading == null ? null : new BigDecimal(meterReading);

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
        OcrReadingResult result = OcrReadingResult.builder()
                .adjustedReading(adjustedReading)
                .rawMeterReading(meterReading)
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

    /** Whether FlowVision reported {@code result.status = NOMETER}: the photo contains no meter. */
    private static boolean isNoMeter(Map<String, Object> resultMap) {
        Object status = resultMap == null ? null : resultMap.get("status");
        return status != null && NO_METER_STATUS.equalsIgnoreCase(status.toString().trim());
    }

    private OcrReadingResult handleFlowVisionErrorResponse(RestClientResponseException ex, String readingUrl, String requestId) {
        String errorMsg = extractFlowVisionErrorMsg(ex.getResponseBodyAsString());
        if (errorMsg != null) {
            log.warn("FlowVision OCR rejected imageUrlHash={} status={} errorMsg={}",
                    imageUrlHash(readingUrl),
                    ex.getStatusCode(),
                    sanitizeLogValue(errorMsg));
            return OcrReadingResult.builder()
                    .rejectionReason(errorMsg)
                    .requestId(requestId)
                    .qualityStatus("REJECTED")
                    .correlationId(UUID.randomUUID().toString())
                    .build();
        }
        log.error("FlowVision OCR HTTP error for imageUrlHash={}: {}", imageUrlHash(readingUrl), ex.getMessage(), ex);
        return null;
    }

    /**
     * The {@code data.meterReading} string exactly as FlowVision returned it (built from its
     * {@code selectedDigit}s). FlowVision places the decimal point itself (a red last digit comes back
     * as {@code "012644.8"}), so the reading is used as returned. Preserves digit count and leading
     * zeros so rollover candidate enumeration can reason positionally. {@code null} when absent or blank.
     */
    private String extractMeterReading(Map<String, Object> dataMap) {
        Object meterReadingObj = dataMap.get("meterReading");
        if (meterReadingObj == null) {
            return null;
        }
        String meterReading = meterReadingObj.toString().trim();
        return meterReading.isEmpty() ? null : meterReading;
    }

    /**
     * TRANSITIONAL — remove once FlowVision places the decimal point in every environment, and use the
     * reading as returned. FlowVision's previous version returned digits only and marked a decimal last
     * digit red; until then such a reading keeps telemetry's previous rule, whatever its length: its last
     * digit goes after the point ({@code ".5"} for one digit, so the digits rollover positions count stay
     * the same). A reading that already has a point is FlowVision's own and is left alone, so readings
     * stay right whichever service is deployed or rolled back first.
     */
    private static String pointOldFormatRedReading(String meterReading, Object lastDigitColor) {
        if (meterReading == null || meterReading.indexOf('.') >= 0
                || lastDigitColor == null || !"red".equalsIgnoreCase(lastDigitColor.toString().trim())) {
            return meterReading;
        }
        int last = meterReading.length() - 1;
        return meterReading.substring(0, last) + "." + meterReading.substring(last);
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

    private String summarizeFlowVisionResult(OcrReadingResult result) {
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

    private OcrReadingResult rejectedFlowVisionResult(Map<String, Object> responseBody, String requestId) {
        String errorMsg = extractFlowVisionErrorMsg(responseBody);
        return OcrReadingResult.builder()
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
