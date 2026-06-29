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

            ResponseEntity<Map> responseEntity = restTemplate.exchange(
                    flowVisionUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );


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
            String lastDigitColor = parseLastDigitColor(dataMap);

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

            return FlowVisionResult.builder()
                    .adjustedReading(adjustedReading)
                    .qualityStatus(qualityStatus)
                    .qualityConfidence(qualityConfidence)
                    .lastDigitColor(lastDigitColor)
                    .correlationId(correlationId)
                    .build();

        } catch (Exception ex) {
            log.error("FlowVision OCR call failed for image {}", readingUrl, ex);
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
        String lastDigitColor = parseLastDigitColor(dataMap);

        if (lastDigitColor == null || !"red".equals(lastDigitColor.toLowerCase(Locale.ROOT))) {
            return parsedReading;
        }

        return parsedReading.movePointLeft(1).setScale(1, RoundingMode.UNNECESSARY);
    }

    private String parseLastDigitColor(Map<String, Object> dataMap) {
        Object lastDigitColorObj = dataMap.get("lastDigitColor");
        if (lastDigitColorObj == null) {
            return null;
        }
        String lastDigitColor = lastDigitColorObj.toString().trim();
        return lastDigitColor.isEmpty() ? null : lastDigitColor;
    }

}
