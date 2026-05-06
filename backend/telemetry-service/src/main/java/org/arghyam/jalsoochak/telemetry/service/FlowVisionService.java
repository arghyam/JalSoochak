package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    @PostConstruct
    public void validateRetryProperties() {
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException("flowvision.retry.max-attempts must be >= 1, got: " + retryMaxAttempts);
        }
        if (retryInitialBackoffMs < 0) {
            throw new IllegalArgumentException("flowvision.retry.initial-backoff-ms must be >= 0, got: " + retryInitialBackoffMs);
        }
    }

    public Optional<FlowVisionResult> extractReading(String readingUrl) {
        Exception lastException = null;
        long backoffMs = retryInitialBackoffMs;

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                FlowVisionResult result = doExtractReading(readingUrl);
                if (attempt > 1) {
                    log.info("FlowVision succeeded on attempt {}/{}", attempt, retryMaxAttempts);
                }
                return Optional.of(result);
            } catch (Exception ex) {
                // Only retry transient/retryable errors
                if (isNonRetryable(ex)) {
                    log.error("FlowVision non-retryable error for image {}: {}", readingUrl, ex.getMessage());
                    throw ex;
                }
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
        return Optional.empty();
    }

    private boolean isNonRetryable(Exception ex) {
        // 4xx client errors (except 429) are non-retryable
        if (ex instanceof HttpClientErrorException) {
            HttpClientErrorException httpEx = (HttpClientErrorException) ex;
            return httpEx.getStatusCode().value() != 429;
        }
        // Parsing and validation errors are non-retryable
        return ex instanceof NumberFormatException
                || ex instanceof ClassCastException
                || ex instanceof IllegalArgumentException;
    }

    private FlowVisionResult doExtractReading(String readingUrl) {
        Map<String, String> payload = new HashMap<>();
        payload.put("imageURL", readingUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> requestEntity =
                new HttpEntity<>(payload, headers);

        ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(
                flowvisionUrl,
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("FlowVision HTTP error: " + responseEntity.getStatusCode());
        }

        Map<String, Object> responseBody = responseEntity.getBody();

        if (responseBody == null || !responseBody.containsKey("result")) {
            log.error("FlowVision response missing 'result'");
            throw new IllegalArgumentException("FlowVision response missing 'result'");
        }

        Object resultObj = responseBody.get("result");
        if (!(resultObj instanceof Map)) {
            log.error("FlowVision 'result' is not a Map: {}", resultObj);
            throw new IllegalArgumentException("FlowVision 'result' is not a Map: " + resultObj);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) resultObj;

        if (!"SUCCESS".equals(resultMap.get("status"))) {
            log.warn("FlowVision OCR not successful: {}", resultMap);
            throw new IllegalArgumentException("FlowVision OCR not successful: " + resultMap.get("status"));
        }

        if (!resultMap.containsKey("data")) {
            log.error("FlowVision result missing 'data'");
            throw new IllegalArgumentException("FlowVision result missing 'data'");
        }

        Object dataObj = resultMap.get("data");
        if (!(dataObj instanceof Map)) {
            log.error("FlowVision 'data' is not a Map: {}", dataObj);
            throw new IllegalArgumentException("FlowVision 'data' is not a Map: " + dataObj);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) dataObj;

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

        Object correlationIdObj = resultMap.get("correlationId");
        String correlationId = (correlationIdObj != null && !correlationIdObj.toString().isEmpty())
                ? correlationIdObj.toString()
                : UUID.randomUUID().toString();

        return FlowVisionResult.builder()
                .adjustedReading(adjustedReading)
                .qualityStatus(qualityStatus)
                .qualityConfidence(qualityConfidence)
                .correlationId(correlationId)
                .build();
    }

}
