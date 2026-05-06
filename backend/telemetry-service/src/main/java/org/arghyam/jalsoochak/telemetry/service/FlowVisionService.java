package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class FlowVisionService {

    @Value("${flowvision.url}")
    private String flowvisionUrl;

    @Value("${flowvision.retry.max-attempts:3}")
    private int retryMaxAttempts = 1;

    @Value("${flowvision.retry.initial-backoff-ms:300}")
    private long retryInitialBackoffMs = 0;

    private final RestTemplate restTemplate;

    public FlowVisionService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public FlowVisionResult extractReading(String readingUrl) {
        Exception lastException = null;
        long backoffMs = retryInitialBackoffMs;

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                FlowVisionResult result = doExtractReading(readingUrl);
                if (attempt > 1) {
                    log.info("FlowVision succeeded on attempt {}/{}", attempt, retryMaxAttempts);
                }
                return result;
            } catch (Exception ex) {
                lastException = ex;
                if (attempt < retryMaxAttempts) {
                    log.warn("FlowVision attempt {}/{} failed for image {}, retrying in {}ms: {}",
                            attempt, retryMaxAttempts, readingUrl, backoffMs, ex.getMessage());
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    backoffMs *= 2;
                }
            }
        }
        log.error("FlowVision OCR call failed after {} attempts for image {}", retryMaxAttempts, readingUrl, lastException);
        return null;
    }

    private FlowVisionResult doExtractReading(String readingUrl) {
        Map<String, String> payload = new HashMap<>();
        payload.put("imageURL", readingUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> requestEntity =
                new HttpEntity<>(payload, headers);

        ResponseEntity<Map> responseEntity = restTemplate.exchange(
                flowvisionUrl,
                HttpMethod.POST,
                requestEntity,
                Map.class
        );

        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("FlowVision HTTP error: " + responseEntity.getStatusCode());
        }

        Map<String, Object> responseBody = responseEntity.getBody();

        if (responseBody == null || !responseBody.containsKey("result")) {
            log.error("FlowVision response missing 'result'");
            return null;
        }

        Map<String, Object> resultMap = (Map<String, Object>) responseBody.get("result");

        if (resultMap == null || !"SUCCESS".equals(resultMap.get("status"))) {
            log.warn("FlowVision OCR not successful: {}", resultMap);
            return null;
        }

        if (!resultMap.containsKey("data")) {
            log.error("FlowVision result missing 'data'");
            return null;
        }

        Map<String, Object> dataMap = (Map<String, Object>) resultMap.get("data");

        BigDecimal adjustedReading = null;
        Object meterReadingObj = dataMap.get("meterReading");
        if (meterReadingObj != null) {
            adjustedReading = new BigDecimal(meterReadingObj.toString());
        }

        String qualityStatus = dataMap.getOrDefault("qualityStatus", "unknown").toString();

        BigDecimal qualityConfidence = null;
        Object confidenceObj = dataMap.get("qualityConfidence");
        if (confidenceObj != null) {
            qualityConfidence = new BigDecimal(confidenceObj.toString());
        }

        String correlationId = resultMap
                .getOrDefault("correlationId", UUID.randomUUID().toString())
                .toString();

        return FlowVisionResult.builder()
                .adjustedReading(adjustedReading)
                .qualityStatus(qualityStatus)
                .qualityConfidence(qualityConfidence)
                .correlationId(correlationId)
                .build();
    }

}
