package org.arghyam.jalsoochak.telemetry.provider.whatsapp.glific;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.ConversationResumeGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
@Slf4j
public class GlificConversationResumeGateway implements ConversationResumeGateway {

    private static final String SESSION_PATH = "/api/v1/session";
    private static final String GRAPHQL_PATH = "/api";
    /** Used when Glific's login response carries no parseable {@code token_expiry_time}. */
    private static final Duration DEFAULT_TOKEN_LIFETIME = Duration.ofMinutes(20);
    /** A token this close to expiry is treated as expired, so a call never starts with a dying one. */
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(60);
    private static final int CONTACT_ID_CACHE_LIMIT = 10_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // One login and one lookup per resume cost 2.1 s + 0.3 s on dev, spent while the operator waits
    // on "Please wait a moment". The token is reused until Glific says it expires; a 401 drops it.
    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiresAt = Instant.EPOCH;
    private final Map<String, String> glificContactIdByPhone = new ConcurrentHashMap<>();

    @Value("${whatsapp.resume.enabled:false}")
    private boolean resumeEnabled;

    @Value("${whatsapp.resume.base-url:https://api.staging.glific.com}")
    private String glificBaseUrl;

    @Value("${whatsapp.resume.user.phone:}")
    private String glificUserPhone;

    @Value("${whatsapp.resume.user.password:}")
    private String glificUserPassword;

    @Value("${whatsapp.resume.flow-id:37172}")
    private String flowId;

    public GlificConversationResumeGateway(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Refuses to start when resume is enabled but a setting it needs is blank. Otherwise every reading
     * would skip the resume with a WARN, and operators would silently stop getting the flow's reply.
     */
    @PostConstruct
    void validateConfiguration() {
        if (!resumeEnabled) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (glificBaseUrl == null || glificBaseUrl.isBlank()) {
            missing.add("whatsapp.resume.base-url (WHATSAPP_RESUME_BASE_URL)");
        }
        if (flowId == null || flowId.isBlank()) {
            missing.add("whatsapp.resume.flow-id (WHATSAPP_RESUME_FLOW_ID)");
        }
        if (glificUserPhone == null || glificUserPhone.isBlank()) {
            missing.add("whatsapp.resume.user.phone (WHATSAPP_RESUME_USER_PHONE, or WHATSAPP_SYNC_USER_PHONE)");
        }
        if (glificUserPassword == null || glificUserPassword.isBlank()) {
            missing.add("whatsapp.resume.user.password"
                    + " (WHATSAPP_RESUME_USER_PASSWORD, or WHATSAPP_SYNC_USER_PASSWORD)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("whatsapp.resume.enabled=true but " + String.join(", ", missing)
                    + " resolved empty. Set them, or set WHATSAPP_RESUME_ENABLED=false.");
        }
    }

    @Override
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

        String maskedContact = maskPhone(contactId);
        try {
            String[] session = {currentAccessToken()};
            if (session[0] == null) {
                log.warn("Skipping Glific flow resume because access token is unavailable (jobId={})", jobId);
                return;
            }

            String glificContactId = glificContactIdByPhone.get(contactId);
            if (glificContactId == null) {
                glificContactId = withAccessToken(session, token -> resolveGlificContactId(token, contactId, jobId));
                if (glificContactId == null || glificContactId.isBlank()) {
                    log.warn("Skipping Glific resume because contact id could not be resolved from phone {} (jobId={})",
                            maskedContact, jobId);
                    return;
                }
                rememberContactId(contactId, glificContactId);
            }

            String resolvedContactId = glificContactId;
            log.info("Calling Glific resumeContactFlow (flowId={}, phone={}, providerContactId={}, jobId={})",
                    flowId, maskedContact, resolvedContactId, jobId);
            Map<String, Object> responseBody = withAccessToken(session,
                    token -> executeResumeMutation(token, resolvedContactId, jobId, result));
            if (responseBody == null) {
                log.warn("Glific resume response body was empty (jobId={})", jobId);
                return;
            }

            if (hasErrors(responseBody)) {
                log.warn("Glific resume returned GraphQL errors for phone {} (jobId={}): {}",
                        maskedContact, jobId, extractErrorsSummary(responseBody));
                return;
            }

            if (!isResumeSuccess(responseBody)) {
                log.warn("Glific resume reported unsuccessful mutation for phone {} (jobId={})", maskedContact, jobId);
                return;
            }

            log.info("Successfully resumed Glific flow {} for phone {} with glificContactId {} (jobId={})",
                    flowId, maskedContact, resolvedContactId, jobId);
        } catch (Exception e) {
            log.error("Failed to resume Glific flow for contactId {} (jobId={}): {}", maskedContact, jobId, e.getMessage(), e);
        }
    }

    /**
     * Runs {@code call} with this resume's session token, logging in again and retrying once when
     * Glific answers 401 — the token expired or was revoked since it was cached. {@code session} is
     * the resume's one-element token holder, so a refreshed token is reused by its later calls.
     */
    private <T> T withAccessToken(String[] session, Function<String, T> call) {
        try {
            return call.apply(session[0]);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                throw e;
            }
            log.info("Glific rejected the cached session token; logging in again");
            invalidateAccessToken();
            session[0] = currentAccessToken();
            return session[0] == null ? null : call.apply(session[0]);
        }
    }

    /** The cached token while it is comfortably inside its lifetime, else a freshly issued one. */
    private synchronized String currentAccessToken() {
        if (cachedAccessToken != null
                && Instant.now().isBefore(cachedAccessTokenExpiresAt.minus(TOKEN_EXPIRY_MARGIN))) {
            return cachedAccessToken;
        }
        cachedAccessToken = null;
        fetchAccessToken();
        return cachedAccessToken;
    }

    private synchronized void invalidateAccessToken() {
        cachedAccessToken = null;
        cachedAccessTokenExpiresAt = Instant.EPOCH;
    }

    private void rememberContactId(String phone, String glificContactId) {
        if (glificContactIdByPhone.size() >= CONTACT_ID_CACHE_LIMIT) {
            glificContactIdByPhone.clear();
        }
        glificContactIdByPhone.put(phone, glificContactId);
    }

    private static Instant parseExpiry(Object expiry) {
        if (expiry == null) {
            return Instant.now().plus(DEFAULT_TOKEN_LIFETIME);
        }
        String text = String.valueOf(expiry).trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException notOffset) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException notInstant) {
                return Instant.now().plus(DEFAULT_TOKEN_LIFETIME);
            }
        }
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "n/a";
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? "****" : "****" + digits.substring(digits.length() - 4);
    }

    @SuppressWarnings("unchecked")
    private void fetchAccessToken() {
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
            return;
        }

        Object data = response.getBody().get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return;
        }
        Object token = dataMap.get("access_token");
        String accessToken = token == null ? null : String.valueOf(token);
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("Glific login succeeded but access token was missing/blank for flow resume");
            return;
        }

        cachedAccessToken = accessToken;
        cachedAccessTokenExpiresAt = parseExpiry(dataMap.get("token_expiry_time"));
        log.info("Glific login successful for flow resume");
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
        // LOCATION-AFFINITY: the same flag the synchronous /location response carries, so a flow
        // resumed after async image processing can branch on one variable name either way.
        resultPayload.put("location_mismatch", result != null && result.isLocationMismatch());
        // The closing line for a recorded reading, so the flow can end without a /closing call.
        resultPayload.put("closing_message", result != null ? result.getClosingMessage() : null);

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
                        maskPhone(candidate), jobId, extractErrorsSummary(responseBody));
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
                log.info("Resolved Glific contact id {} for phone {} (jobId={})", glificContactId, maskPhone(candidate), jobId);
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
