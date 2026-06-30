package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
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

        try {

            Map<String, String> payload = new HashMap<>();
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
                return null;
            }

            Map<String, Object> responseBody = responseEntity.getBody();

            if (responseBody == null || !responseBody.containsKey("result")) {
                log.error("FlowVision response missing 'result'");
                return null;
            }

            Map<String, Object> resultMap =
                    (Map<String, Object>) responseBody.get("result");

            if (resultMap == null || !"SUCCESS".equals(resultMap.get("status"))) {
                log.warn("FlowVision OCR not successful: {}", resultMap);
                return null;
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

            FlowVisionResult result = FlowVisionResult.builder()
                    .adjustedReading(adjustedReading)
                    .qualityStatus(qualityStatus)
                    .qualityConfidence(qualityConfidence)
                    .correlationId(correlationId)
                    .build();
            log.info("flowvision parsed_result imageUrlHash={} result={}",
                    imageUrlHash(readingUrl),
                    summarizeFlowVisionResult(result));
            return result;

        } catch (Exception ex) {
            log.error("FlowVision OCR call failed for imageUrlHash={}: {}", imageUrlHash(readingUrl), ex.getMessage(), ex);
            if (log.isDebugEnabled()) {
                log.debug("FlowVision OCR call failed for image {}", readingUrl);
            }
            return null;
        }
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
