package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GlificFlowResumeService {

    private static final String GRAPHQL_PATH = "/api";
    private static final String SESSION_PATH = "/api/v1/session";

    private static final String RESUME_CONTACT_FLOW_MUTATION = """
            mutation resumeContactFlow($flowId: ID!, $contactId: ID!, $result: Json!) {
              resumeContactFlow(flowId: $flowId, contactId: $contactId, result: $result) {
                success
                errors {
                  key
                  message
                }
              }
            }
            """;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${glific.readings.resume.enabled:true}")
    private boolean resumeEnabled;

    @Value("${glific.readings.resume.flow-id:37172}")
    private String readingsResumeFlowId;

    @Value("${glific.readings.resume.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${glific.readings.resume.retry.initial-backoff-ms:500}")
    private long retryInitialBackoffMs;

    @Value("${glific.readings.resume.retry.max-backoff-ms:4000}")
    private long retryMaxBackoffMs;

    @Value("${glific.readings.resume.token-ttl-ms:1500000}")
    private long tokenTtlMs;

    @Value("${glific.sync.base-url:https://api.arghyam.glific.com}")
    private String glificBaseUrl;

    @Value("${glific.sync.user.phone:}")
    private String glificUserPhone;

    @Value("${glific.sync.user.password:}")
    private String glificUserPassword;

    private volatile String cachedAccessToken;
    private volatile long cachedAccessTokenEpochMs;

    public GlificFlowResumeService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public void resumeReadingsFlow(String contactId, String jobId, CreateReadingResponse response) {
        if (!resumeEnabled) {
            return;
        }
        if (contactId == null || contactId.isBlank()) {
            log.warn("Skipping flow resume because contactId is missing (jobId={})", jobId);
            return;
        }
        if (readingsResumeFlowId == null || readingsResumeFlowId.isBlank()) {
            log.warn("Skipping flow resume because readings flow-id is not configured");
            return;
        }
        if (glificUserPhone == null || glificUserPhone.isBlank()
                || glificUserPassword == null || glificUserPassword.isBlank()) {
            log.warn("Skipping flow resume because Glific credentials are not configured");
            return;
        }

        Map<String, Object> responseMap = objectMapper.convertValue(response, new TypeReference<>() {});
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", jobId);
        result.put("result", responseMap);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("flowId", readingsResumeFlowId);
        variables.put("contactId", contactId);
        variables.put("result", result);

        Map<String, Object> requestBody = Map.of(
                "query", RESUME_CONTACT_FLOW_MUTATION,
                "variables", variables
        );
        int attempts = Math.max(1, retryMaxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String accessToken = getAccessToken(false);
                if (accessToken == null || accessToken.isBlank()) {
                    log.warn("Skipping flow resume because Glific access token could not be fetched (jobId={})", jobId);
                    return;
                }

                HttpHeaders headers = defaultAuthHeaders(accessToken);
                ResponseEntity<Map> responseEntity = restTemplate.postForEntity(
                        resolveUrl(GRAPHQL_PATH),
                        new HttpEntity<>(requestBody, headers),
                        Map.class
                );

                if (!responseEntity.getStatusCode().is2xxSuccessful() || responseEntity.getBody() == null) {
                    log.warn("Glific resume flow failed for contactId {} with status {}",
                            contactId, responseEntity.getStatusCode());
                    return;
                }

                Object errors = responseEntity.getBody().get("errors");
                if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
                    if (containsAuthError(errorList) && attempt < attempts) {
                        getAccessToken(true);
                        sleepBackoff(attempt);
                        continue;
                    }
                    log.warn("Glific resume flow returned errors for contactId {}: {}", contactId, errorList);
                    return;
                }

                boolean success = extractResumeSuccess(responseEntity.getBody());
                if (!success) {
                    log.warn("Glific resume flow returned unsuccessful response for contactId {}", contactId);
                    return;
                }

                log.debug("Glific flow resumed successfully for contactId {} flowId {} jobId {}",
                        contactId, readingsResumeFlowId, jobId);
                return;
            } catch (RestClientResponseException e) {
                int status = e.getRawStatusCode();
                boolean authError = status == 401 || status == 403;
                boolean retryable = authError || status == 408 || status == 429 || status >= 500;
                if (authError) {
                    getAccessToken(true);
                }
                if (!retryable || attempt >= attempts) {
                    log.error("Glific resume flow HTTP failure for contactId {} attempt {}/{} status={} body={}",
                            contactId, attempt, attempts, status, e.getResponseBodyAsString(), e);
                    return;
                }
                log.warn("Glific resume flow transient HTTP failure for contactId {} attempt {}/{} status={} retrying",
                        contactId, attempt, attempts, status);
                sleepBackoff(attempt);
            } catch (ResourceAccessException e) {
                if (attempt >= attempts) {
                    log.error("Glific resume flow timeout/connect failure for contactId {} attempt {}/{}",
                            contactId, attempt, attempts, e);
                    return;
                }
                log.warn("Glific resume flow timeout/connect failure for contactId {} attempt {}/{} retrying",
                        contactId, attempt, attempts);
                sleepBackoff(attempt);
            } catch (RestClientException e) {
                if (attempt >= attempts) {
                    log.error("Glific resume flow client failure for contactId {} attempt {}/{}",
                            contactId, attempt, attempts, e);
                    return;
                }
                log.warn("Glific resume flow client failure for contactId {} attempt {}/{} retrying",
                        contactId, attempt, attempts);
                sleepBackoff(attempt);
            } catch (Exception e) {
                log.error("Unexpected error while resuming Glific flow for contactId {}", contactId, e);
                return;
            }
        }
    }

    private boolean containsAuthError(List<?> errors) {
        for (Object error : errors) {
            String text = String.valueOf(error).toLowerCase();
            if (text.contains("unauthenticated") || text.contains("unauthorized")) {
                return true;
            }
        }
        return false;
    }

    private synchronized String getAccessToken(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(1_000L, tokenTtlMs);
        if (!forceRefresh && cachedAccessToken != null && !cachedAccessToken.isBlank()
                && (now - cachedAccessTokenEpochMs) < ttl) {
            return cachedAccessToken;
        }
        String token = fetchAccessToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        cachedAccessToken = token;
        cachedAccessTokenEpochMs = now;
        return token;
    }

    @SuppressWarnings("unchecked")
    private String fetchAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("phone", glificUserPhone.trim());
        user.put("password", glificUserPassword.trim());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                resolveUrl(SESSION_PATH),
                new HttpEntity<>(body, headers),
                Map.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return null;
        }
        Object data = response.getBody().get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        Object accessToken = dataMap.get("access_token");
        return accessToken == null ? null : String.valueOf(accessToken);
    }

    private void sleepBackoff(int attempt) {
        long initial = Math.max(0L, retryInitialBackoffMs);
        long max = Math.max(initial, retryMaxBackoffMs);
        long backoff = initial;
        for (int i = 1; i < attempt; i++) {
            backoff = Math.min(max, backoff * 2);
        }
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean extractResumeSuccess(Map responseBody) {
        Object data = responseBody.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return false;
        }
        Object resumeContactFlow = dataMap.get("resumeContactFlow");
        if (!(resumeContactFlow instanceof Map<?, ?> resumeContactFlowMap)) {
            return false;
        }
        Object success = resumeContactFlowMap.get("success");
        return success instanceof Boolean && (Boolean) success;
    }

    private HttpHeaders defaultAuthHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", accessToken);
        return headers;
    }

    private String resolveUrl(String path) {
        String base = glificBaseUrl == null ? "" : glificBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
