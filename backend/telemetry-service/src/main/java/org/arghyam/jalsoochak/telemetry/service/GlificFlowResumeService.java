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

    @Value("${glific.sync.base-url:https://api.arghyam.glific.com}")
    private String glificBaseUrl;

    @Value("${glific.sync.user.phone:}")
    private String glificUserPhone;

    @Value("${glific.sync.user.password:}")
    private String glificUserPassword;

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

        String accessToken = fetchAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Skipping flow resume because Glific access token could not be fetched (jobId={})", jobId);
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

        HttpHeaders headers = defaultAuthHeaders(accessToken);
        Map<String, Object> requestBody = Map.of(
                "query", RESUME_CONTACT_FLOW_MUTATION,
                "variables", variables
        );
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
