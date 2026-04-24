package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Glific UI messages/options from the consolidated tenant config:
 * config_key = GLIFIC_MESSAGE_TEMPLATES, config_value = JSON.
 *
 * Falls back to empty results if the config is missing/invalid; callers can then use legacy per-key configs.
 */
@Service
@Slf4j
public class GlificMessageTemplatesService {
    public static final String CONFIG_KEY = "GLIFIC_MESSAGE_TEMPLATES";

    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;
    @Value("${telemetry.cache.templates.enabled:true}")
    private boolean templatesCacheEnabled = true;
    @Value("${telemetry.cache.templates.ttl-ms:120000}")
    private long templatesCacheTtlMs = 120_000L;
    private final Map<Integer, TimedCacheValue<Optional<JsonNode>>> templatesCache = new ConcurrentHashMap<>();

    public GlificMessageTemplatesService(TenantConfigRepository tenantConfigRepository, ObjectMapper objectMapper) {
        this.tenantConfigRepository = tenantConfigRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<JsonNode> loadTemplates(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        if (!templatesCacheEnabled || templatesCacheTtlMs <= 0L) {
            return loadTemplatesFromRepository(tenantId);
        }
        long now = System.currentTimeMillis();
        TimedCacheValue<Optional<JsonNode>> cached = templatesCache.get(tenantId);
        if (cached != null && !cached.isExpired(now)) {
            return cached.value();
        }
        Optional<JsonNode> refreshed = loadTemplatesFromRepository(tenantId);
        templatesCache.put(tenantId, new TimedCacheValue<>(refreshed, now + templatesCacheTtlMs));
        return refreshed;
    }

    public void invalidateTemplatesCache(Integer tenantId) {
        if (tenantId == null) {
            return;
        }
        templatesCache.remove(tenantId);
    }

    public void invalidateAllTemplatesCache() {
        templatesCache.clear();
    }

    public Optional<String> resolveScreenText(Integer tenantId,
                                              String screenKey,
                                              String fieldKey,
                                              String languageKey) {
        return loadTemplates(tenantId)
                .flatMap(root -> resolveLocalizedText(root, screenKey, fieldKey, languageKey));
    }

    public Optional<String> resolveScreenPrompt(Integer tenantId, String screenKey, String languageKey) {
        return resolveScreenText(tenantId, screenKey, "prompt", languageKey);
    }

    public Optional<String> resolveScreenMessage(Integer tenantId, String screenKey, String languageKey) {
        return resolveScreenText(tenantId, screenKey, "message", languageKey);
    }

    public Optional<String> resolveScreenConfirmationTemplate(Integer tenantId, String screenKey, String languageKey) {
        return resolveScreenText(tenantId, screenKey, "confirmationTemplate", languageKey);
    }

    public List<TemplateOption> resolveScreenOptions(Integer tenantId, String screenKey) {
        return resolveOrderedOptions(tenantId, screenKey, "options");
    }

    public List<TemplateOption> resolveScreenReasons(Integer tenantId, String screenKey) {
        return resolveOrderedOptions(tenantId, screenKey, "reasons");
    }

    private List<TemplateOption> resolveOrderedOptions(Integer tenantId, String screenKey, String containerKey) {
        Optional<JsonNode> rootOpt = loadTemplates(tenantId);
        if (rootOpt.isEmpty()) {
            return List.of();
        }
        JsonNode root = rootOpt.get();
        JsonNode container = root.path("screens").path(screenKey).path(containerKey);
        if (container.isMissingNode() || !container.isObject()) {
            return List.of();
        }

        List<TemplateOption> options = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = container.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            int order = value.path("order").asInt(0);
            Map<String, String> labels = readStringMap(value.path("label"));
            options.add(new TemplateOption(key, order, labels));
        }
        options.sort(Comparator
                .comparingInt(TemplateOption::order)
                .thenComparing(TemplateOption::key, String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    private Optional<String> resolveLocalizedText(JsonNode root,
                                                  String screenKey,
                                                  String fieldKey,
                                                  String languageKey) {
        if (root == null || root.isNull()) {
            return Optional.empty();
        }
        JsonNode field = root.path("screens").path(screenKey).path(fieldKey);
        if (field.isMissingNode() || field.isNull()) {
            return Optional.empty();
        }
        if (field.isTextual()) {
            return Optional.of(field.asText());
        }
        if (!field.isObject()) {
            return Optional.empty();
        }

        String langCode = toTemplateLanguageCode(languageKey);
        String localized = readText(field, langCode).orElse(null);
        if (localized == null || localized.isBlank()) {
            localized = readText(field, "en").orElse(null);
        }
        if (localized == null || localized.isBlank()) {
            localized = readFirstText(field).orElse(null);
        }
        return (localized == null || localized.isBlank()) ? Optional.empty() : Optional.of(localized);
    }

    private Map<String, String> readStringMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(node, new TypeReference<>() {
            });
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private Optional<String> readText(JsonNode node, String key) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode child = node.get(key);
        if (child == null || child.isNull() || !child.isTextual()) {
            return Optional.empty();
        }
        String text = child.asText();
        return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text);
    }

    private Optional<String> readFirstText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Optional.empty();
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue() != null && entry.getValue().isTextual()) {
                String text = entry.getValue().asText();
                if (text != null && !text.isBlank()) {
                    return Optional.of(text);
                }
            }
        }
        return Optional.empty();
    }

    public static String toTemplateLanguageCode(String normalizedLanguageKey) {
        if (normalizedLanguageKey == null) {
            return "en";
        }
        String k = normalizedLanguageKey.trim().toLowerCase(Locale.ROOT);
        if ("hindi".equals(k) || "hi".equals(k)) {
            return "hi";
        }
        if ("english".equals(k) || "en".equals(k)) {
            return "en";
        }
        // Most tenants today use "en"/"hi" in the JSON; default to "en".
        return "en";
    }

    public record TemplateOption(String key, int order, Map<String, String> labelByLang) {
        public String labelForLanguageKey(String normalizedLanguageKey) {
            String langCode = toTemplateLanguageCode(normalizedLanguageKey);
            String label = labelByLang.get(langCode);
            if (label == null || label.isBlank()) {
                label = labelByLang.get("en");
            }
            if (label == null || label.isBlank()) {
                label = labelByLang.values().stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
            }
            return label;
        }

        public String canonicalLabel() {
            String label = labelByLang.get("en");
            if (label == null || label.isBlank()) {
                return labelForLanguageKey("en");
            }
            return label;
        }

        public boolean matchesAnyLabel(String input) {
            if (input == null) {
                return false;
            }
            String trimmed = input.trim();
            if (trimmed.isBlank()) {
                return false;
            }
            for (String label : labelByLang.values()) {
                if (label != null && label.equalsIgnoreCase(trimmed)) {
                    return true;
                }
            }
            return canonicalLabel() != null && canonicalLabel().equalsIgnoreCase(trimmed);
        }
    }

    private Optional<JsonNode> loadTemplatesFromRepository(Integer tenantId) {
        return tenantConfigRepository.findConfigValue(tenantId, CONFIG_KEY)
                .flatMap(raw -> {
                    try {
                        JsonNode root = objectMapper.readTree(raw);
                        if (root == null || root.isNull()) {
                            return Optional.empty();
                        }
                        return Optional.of(root);
                    } catch (Exception e) {
                        log.warn("Invalid {} JSON for tenantId {}: {}", CONFIG_KEY, tenantId, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    private record TimedCacheValue<T>(T value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
