package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class FlowVisionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;
    private final String flowVisionUrl;

    public FlowVisionService(
            RestTemplate restTemplate,
            @Value("${flowvision.url}") String flowVisionUrl
    ) {
        this.restTemplate = restTemplate;
        this.flowVisionUrl = flowVisionUrl;
    }

    public FlowVisionResult extractReading(String readingUrl) {
        String requestId = UUID.randomUUID().toString();

        try {
            return requestFlowVisionReading(readingUrl, requestId);
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

    public FlowVisionResult extractReadingOrThrow(String readingUrl) {
        String requestId = UUID.randomUUID().toString();
        try {
            return requestFlowVisionReading(readingUrl, requestId);
        } catch (RestClientResponseException ex) {
            if (FlowVisionTransientFailures.isServiceUnavailable(ex)) {
                // Let the retry mechanism handle transient FlowVision failures.
                throw ex;
            }
            return handleFlowVisionErrorResponse(ex, readingUrl, requestId);
        }
        // Transient failures such as ResourceAccessException propagate for retry.
    }

    private FlowVisionResult requestFlowVisionReading(String readingUrl, String requestId) {
        Map<String, String> payload = new HashMap<>();
        payload.put("id", requestId);
        payload.put("imageURL", readingUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> requestEntity =
                new HttpEntity<>(payload, headers);

        log.info("flowvision request imageUrlHash={} endpoint={}", imageUrlHash(readingUrl), flowVisionUrl);
        ResponseEntity<Map> responseEntity = restTemplate.exchange(
                flowVisionUrl,
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

        FlowVisionResult result = FlowVisionResult.builder()
                .adjustedReading(adjustedReading)
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
        Object lastDigitColorObj = dataMap.get("lastDigitColor");
        String lastDigitColor = lastDigitColorObj == null
                ? ""
                : lastDigitColorObj.toString().trim().toLowerCase(Locale.ROOT);

        if (!"red".equals(lastDigitColor)) {
            return parsedReading;
        }

        return parsedReading.movePointLeft(1).setScale(1, RoundingMode.UNNECESSARY);
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
