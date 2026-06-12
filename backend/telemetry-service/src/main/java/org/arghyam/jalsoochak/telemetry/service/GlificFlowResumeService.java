package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class GlificFlowResumeService {

    private static final String SESSION_PATH = "/api/v1/session";
    private static final String GRAPHQL_PATH = "/api";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

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

    public GlificFlowResumeService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
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

            String glificContactId = resolveGlificContactId(accessToken, contactId, jobId);
            if (glificContactId == null || glificContactId.isBlank()) {
                log.warn("Skipping Glific resume because contact id could not be resolved from phone {} (jobId={})",
                        contactId, jobId);
                return;
            }

            log.info("Calling Glific resumeContactFlow (flowId={}, phone={}, glificContactId={}, jobId={})",
                    flowId, contactId, glificContactId, jobId);
            Map<String, Object> responseBody = executeResumeMutation(accessToken, glificContactId, jobId, result);
            if (responseBody == null) {
                log.warn("Glific resume response body was empty (jobId={})", jobId);
                return;
            }

            if (hasErrors(responseBody)) {
                log.warn("Glific resume returned GraphQL errors for phone {} (jobId={}): {}",
                        contactId, jobId, extractErrorsSummary(responseBody));
                return;
            }

            if (!isResumeSuccess(responseBody)) {
                log.warn("Glific resume reported unsuccessful mutation for phone {} (jobId={})", contactId, jobId);
                return;
            }

            log.info("Successfully resumed Glific flow {} for phone {} with glificContactId {} (jobId={})",
                    flowId, contactId, glificContactId, jobId);
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
                                                      String glificContactId,
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

        String stringifiedResult = stringifyResult(resultPayload);
        Map<String, Object> variables = new HashMap<>();
        variables.put("flowId", flowId);
        variables.put("contactId", glificContactId);
        variables.put("result", stringifiedResult);

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
    private String resolveGlificContactId(String accessToken, String phone, String jobId) {
        if (phone == null || phone.isBlank()) {
            return null;
        }

        for (String candidate : buildPhoneCandidates(phone)) {
            Map<String, Object> responseBody = executeContactByPhoneQuery(accessToken, candidate);
            if (responseBody == null) {
                continue;
            }
            if (hasErrors(responseBody)) {
                log.warn("Glific contactByPhone returned errors for phone {} (jobId={}): {}",
                        candidate, jobId, extractErrorsSummary(responseBody));
                continue;
            }

            Object data = responseBody.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                continue;
            }
            Object contactByPhone = dataMap.get("contactByPhone");
            if (!(contactByPhone instanceof Map<?, ?> contactByPhoneMap)) {
                continue;
            }
            Object contact = contactByPhoneMap.get("contact");
            if (!(contact instanceof Map<?, ?> contactMap)) {
                continue;
            }
            Object id = contactMap.get("id");
            if (id == null) {
                continue;
            }
            String glificContactId = String.valueOf(id).trim();
            if (!glificContactId.isBlank()) {
                log.info("Resolved Glific contact id {} for phone {} (jobId={})", glificContactId, candidate, jobId);
                return glificContactId;
            }
        }

        return null;
    }

    private List<String> buildPhoneCandidates(String phone) {
        String raw = phone.trim();
        String noPlus = raw.startsWith("+") ? raw.substring(1) : raw;

        Set<String> candidates = new LinkedHashSet<>();
        if (!raw.isBlank()) {
            candidates.add(raw);
        }
        if (!noPlus.isBlank()) {
            candidates.add(noPlus);
            candidates.add("+" + noPlus);
        }
        return new ArrayList<>(candidates);
    }

    private Map<String, Object> executeContactByPhoneQuery(String accessToken, String phone) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", accessToken);

        String query = """
                query contactByPhone($phone: String!) {
                  contactByPhone(phone: $phone) {
                    contact {
                      id
                    }
                  }
                }
                """;

        Map<String, Object> variables = Map.of("phone", phone);
        Map<String, Object> body = Map.of("query", query, "variables", variables);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(resolveUrl(GRAPHQL_PATH), request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private String extractErrorsSummary(Map<String, Object> responseBody) {
        Object errors = responseBody.get("errors");
        if (!(errors instanceof List<?> errorList) || errorList.isEmpty()) {
            return "unknown";
        }

        List<String> parts = new ArrayList<>();
        for (Object errorObj : errorList) {
            if (errorObj instanceof Map<?, ?> errorMap) {
                Object key = errorMap.get("key");
                Object message = errorMap.get("message");
                if (message == null) {
                    message = errorMap.get("msg");
                }
                if (message == null) {
                    message = errorMap.get("details");
                }
                String segment = (key != null ? String.valueOf(key) + ":" : "") + String.valueOf(message);
                parts.add(segment);
            } else {
                parts.add(String.valueOf(errorObj));
            }
        }

        return String.join(" | ", parts);
    }

    private String stringifyResult(Map<String, Object> resultPayload) {
        try {
            return objectMapper.writeValueAsString(resultPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Glific resume result payload", e);
        }
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
