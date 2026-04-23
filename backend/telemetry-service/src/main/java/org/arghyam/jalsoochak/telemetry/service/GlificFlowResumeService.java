package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class GlificFlowResumeService {

    private static final String SESSION_PATH = "/api/v1/session";
    private static final String GRAPHQL_PATH = "/api";

    private final RestTemplate restTemplate;

    @Value("${glific.resume.enabled:false}")
    private boolean resumeEnabled;

    @Value("${glific.resume.base-url:https://api.staging.glific.com}")
    private String glificBaseUrl;

    @Value("${glific.resume.user.phone:}")
    private String glificUserPhone;

    @Value("${glific.resume.user.password:}")
    private String glificUserPassword;

    @Value("${glific.resume.flow-id:37172}")
    private String flowId;

    public GlificFlowResumeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void resumeReadingsFlow(String contactId, String jobId, CreateReadingResponse result) {
        if (!resumeEnabled) {
            log.debug("Glific flow resume is disabled; skipping (jobId={})", jobId);
            return;
        }
        if (contactId == null || contactId.isBlank()) {
            log.warn("Skipping Glific flow resume because contactId is missing (jobId={})", jobId);
            return;
        }
        if (flowId == null || flowId.isBlank()) {
            log.warn("Skipping Glific flow resume because flowId is not configured (jobId={})", jobId);
            return;
        }
        if (glificUserPhone == null || glificUserPhone.isBlank()
                || glificUserPassword == null || glificUserPassword.isBlank()) {
            log.warn("Skipping Glific flow resume because credentials are not configured (jobId={})", jobId);
            return;
        }

        try {
            String accessToken = fetchAccessToken();
            if (accessToken == null || accessToken.isBlank()) {
                log.warn("Skipping Glific flow resume because access token is unavailable (jobId={})", jobId);
                return;
            }

            log.info("Calling Glific resumeContactFlow (flowId={}, contactId={}, jobId={})", flowId, contactId, jobId);
            Map<String, Object> responseBody = executeResumeMutation(accessToken, contactId, jobId, result);
            if (responseBody == null) {
                log.warn("Glific resume response body was empty (jobId={})", jobId);
                return;
            }

            if (hasErrors(responseBody)) {
                log.warn("Glific resume returned GraphQL errors for contactId {} (jobId={})", contactId, jobId);
                return;
            }

            if (!isResumeSuccess(responseBody)) {
                log.warn("Glific resume reported unsuccessful mutation for contactId {} (jobId={})", contactId, jobId);
                return;
            }

            log.info("Successfully resumed Glific flow {} for contactId {} (jobId={})", flowId, contactId, jobId);
        } catch (Exception e) {
            log.error("Failed to resume Glific flow for contactId {} (jobId={}): {}", contactId, jobId, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchAccessToken() {
        log.info("Attempting Glific login for flow resume (baseUrl={})", glificBaseUrl);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> user = new HashMap<>();
        user.put("phone", glificUserPhone.trim());
        user.put("password", glificUserPassword.trim());

        Map<String, Object> body = new HashMap<>();
        body.put("user", user);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(resolveUrl(SESSION_PATH), request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Glific login failed for flow resume (status={})", response.getStatusCode());
            return null;
        }

        Object data = response.getBody().get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        Object token = dataMap.get("access_token");
        String accessToken = token == null ? null : String.valueOf(token);
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Glific login succeeded but access token was missing/blank for flow resume");
            return null;
        }

        log.info("Glific login successful for flow resume");
        return accessToken;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeResumeMutation(String accessToken,
                                                      String contactId,
                                                      String jobId,
                                                      CreateReadingResponse result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", accessToken);

        String mutation = """
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

        Map<String, Object> resultPayload = new HashMap<>();
        resultPayload.put("job_id", jobId);
        resultPayload.put("success", result != null && result.isSuccess());
        resultPayload.put("message", result != null ? result.getMessage() : null);
        resultPayload.put("correlation_id", result != null ? result.getCorrelationId() : null);
        resultPayload.put("meter_reading", result != null ? result.getMeterReading() : null);
        resultPayload.put("quality_status", result != null ? result.getQualityStatus() : null);
        resultPayload.put("quality_confidence", result != null ? result.getQualityConfidence() : null);
        resultPayload.put("last_confirmed_reading", result != null ? result.getLastConfirmedReading() : null);

        Map<String, Object> variables = new HashMap<>();
        variables.put("flowId", flowId);
        variables.put("contactId", contactId);
        variables.put("result", resultPayload);

        Map<String, Object> body = new HashMap<>();
        body.put("query", mutation);
        body.put("variables", variables);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(resolveUrl(GRAPHQL_PATH), request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        return response.getBody();
    }

    private boolean hasErrors(Map<String, Object> responseBody) {
        Object errors = responseBody.get("errors");
        return errors instanceof List<?> errorList && !errorList.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private boolean isResumeSuccess(Map<String, Object> responseBody) {
        Object data = responseBody.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return false;
        }
        Object resumeNode = dataMap.get("resumeContactFlow");
        if (!(resumeNode instanceof Map<?, ?> resumeMap)) {
            return false;
        }
        Object success = resumeMap.get("success");
        return success instanceof Boolean b && b;
    }

    private String resolveUrl(String path) {
        String base = glificBaseUrl == null ? "" : glificBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
