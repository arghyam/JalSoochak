package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class GlificContactSyncService {

    private static final String GRAPHQL_PATH = "/api";
    private static final String SESSION_PATH = "/api/v1/session";

    private static final Map<String, Integer> GLIFIC_LANGUAGE_IDS = buildLanguageMap();

    private final RestTemplate restTemplate;
    private final Executor glificSyncExecutor;

    // Optional data-driven catalog (V36). Injected when the bean/table exist; when null or without a
    // matching row, resolution falls back to the hardcoded GLIFIC_LANGUAGE_IDS map below. Kept as a
    // non-constructor field so existing constructor callers/tests are unaffected.
    @Autowired(required = false)
    private LanguageCatalogRepository languageCatalogRepository;

    @Value("${glific.sync.enabled:false}")
    private boolean glificSyncEnabled;

    @Value("${glific.sync.base-url:https://api.arghyam.glific.com}")
    private String glificBaseUrl;

    @Value("${glific.sync.user.phone:}")
    private String glificUserPhone;

    @Value("${glific.sync.user.password:}")
    private String glificUserPassword;

    public GlificContactSyncService(RestTemplate restTemplate,
                                    @Qualifier("glificSyncExecutor") Executor glificSyncExecutor) {
        this.restTemplate = restTemplate;
        this.glificSyncExecutor = glificSyncExecutor;
    }

    public void syncContactLanguageAsync(String contactPhone, String selectedLanguage) {
        if (!glificSyncEnabled) {
            return;
        }

        String phone = normalizePhone(contactPhone);
        Integer languageId = resolveGlificLanguageId(selectedLanguage);
        if (phone == null || languageId == null) {
            return;
        }
        if (glificUserPhone == null || glificUserPhone.isBlank()
                || glificUserPassword == null || glificUserPassword.isBlank()) {
            return;
        }

        glificSyncExecutor.execute(() -> {
            try {
                syncContactLanguage(phone, languageId);
            } catch (Exception e) {
                log.error("Failed to sync Glific language for languageId {}", languageId, e);
                log.debug("Failed to sync Glific language for phone {} and languageId {}", phone, languageId);
            }
        });
    }

    private void syncContactLanguage(String contactPhone, Integer languageId) {
        String accessToken = fetchAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }

        String contactId = fetchContactId(accessToken, contactPhone);
        if ((contactId == null || contactId.isBlank()) && !contactPhone.startsWith("+")) {
            String plusPhone = "+" + contactPhone;
            contactId = fetchContactId(accessToken, plusPhone);
        }
        if ((contactId == null || contactId.isBlank()) && contactPhone.startsWith("+")) {
            String plainPhone = contactPhone.substring(1);
            contactId = fetchContactId(accessToken, plainPhone);
        }
        if (contactId == null || contactId.isBlank()) {
            return;
        }

        updateContactLanguage(accessToken, contactId, languageId);
    }

    @SuppressWarnings("unchecked")
    private String fetchAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> user = new HashMap<>();
        user.put("phone", glificUserPhone.trim());
        user.put("password", glificUserPassword.trim());

        Map<String, Object> body = new HashMap<>();
        body.put("user", user);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                resolveUrl(SESSION_PATH),
                request,
                Map.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return null;
        }

        Object data = response.getBody().get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        Object token = dataMap.get("access_token");
        return token == null ? null : String.valueOf(token);
    }

    @SuppressWarnings("unchecked")
    private String fetchContactId(String accessToken, String contactPhone) {
        HttpHeaders headers = defaultAuthHeaders(accessToken);
        String query = "query { contacts(filter: {phone: \"" + contactPhone + "\"}) { id name phone } }";
        Map<String, Object> body = Map.of("query", query);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                resolveUrl(GRAPHQL_PATH),
                request,
                Map.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        if (response.getBody() == null) {
            return null;
        }

        Object errors = response.getBody().get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
            return null;
        }

        Object data = response.getBody().get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }

        Object contacts = dataMap.get("contacts");
        if (!(contacts instanceof List<?> contactList)) {
            return null;
        }
        if (contactList.isEmpty()) {
            return null;
        }

        Object first = contactList.get(0);
        if (!(first instanceof Map<?, ?> firstContact)) {
            return null;
        }

        Object id = firstContact.get("id");
        return id == null ? null : String.valueOf(id);
    }

    private void updateContactLanguage(String accessToken, String contactId, Integer languageId) {
        HttpHeaders headers = defaultAuthHeaders(accessToken);
        String mutation = "mutation { updateContact(id: " + contactId
                + ", input: { language_id: " + languageId + " }) "
                + "{ contact { id language { id } } } }";
        Map<String, Object> body = Map.of("query", mutation);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                resolveUrl(GRAPHQL_PATH),
                request,
                Map.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            return;
        }

        if (response.getBody() == null) {
            return;
        }

        Object errors = response.getBody().get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
            return;
        }

        Integer updatedLanguageId = extractUpdatedLanguageId(response.getBody());
        if (updatedLanguageId == null) {
            return;
        }

        if (!updatedLanguageId.equals(languageId)) {
            return;
        }
    }

    @SuppressWarnings("unchecked")
    private Integer extractUpdatedLanguageId(Map responseBody) {
        Object data = responseBody.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return null;
        }
        Object updateContact = dataMap.get("updateContact");
        if (!(updateContact instanceof Map<?, ?> updateContactMap)) {
            return null;
        }
        Object contact = updateContactMap.get("contact");
        if (!(contact instanceof Map<?, ?> contactMap)) {
            return null;
        }
        Object language = contactMap.get("language");
        if (!(language instanceof Map<?, ?> languageMap)) {
            return null;
        }
        Object id = languageMap.get("id");
        if (id == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(id));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = phone.trim();
        if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    // Package-private for unit testing of the resolution/fallback logic.
    Integer resolveGlificLanguageId(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String normalized = normalizeLanguage(language);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.matches("^\\d+$")) {
            return Integer.parseInt(normalized);
        }
        if (languageCatalogRepository != null) {
            Integer fromCatalog = languageCatalogRepository.findLanguageIdByAlias(normalized).orElse(null);
            if (fromCatalog != null) {
                return fromCatalog;
            }
        }
        return GLIFIC_LANGUAGE_IDS.get(normalized);
    }

    private String normalizeLanguage(String language) {
        return language.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();
    }

    private static Map<String, Integer> buildLanguageMap() {
        Map<String, Integer> out = new HashMap<>();
        out.put("english", 1);
        out.put("en", 1);
        out.put("hindi", 2);
        out.put("hi", 2);
        out.put("tamil", 3);
        out.put("ta", 3);
        out.put("kannada", 4);
        out.put("kn", 4);
        out.put("malayalam", 5);
        out.put("ml", 5);
        out.put("telugu", 6);
        out.put("te", 6);
        out.put("odia", 7);
        out.put("or", 7);
        out.put("assamese", 8);
        out.put("as", 8);
        out.put("gujarati", 9);
        out.put("gu", 9);
        out.put("bengali", 10);
        out.put("bn", 10);
        out.put("punjabi", 11);
        out.put("pa", 11);
        out.put("marathi", 12);
        out.put("mr", 12);
        out.put("urdu", 13);
        out.put("ur", 13);
        out.put("spanish", 14);
        out.put("es", 14);
        out.put("sign language", 15);
        out.put("isl", 15);
        out.put("french", 16);
        out.put("fr", 16);
        out.put("swahili", 17);
        out.put("sw", 17);
        out.put("kinyarwanda", 18);
        out.put("rw rw", 18);
        out.put("malay", 20);
        out.put("ms", 20);
        out.put("gondi", 21);
        out.put("gon", 21);
        out.put("indonesian", 22);
        out.put("id", 22);
        return Map.copyOf(out);
    }
}
