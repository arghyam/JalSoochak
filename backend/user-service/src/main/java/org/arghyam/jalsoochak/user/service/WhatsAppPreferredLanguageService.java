package org.arghyam.jalsoochak.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.user.repository.SupportedLanguageRepository;
import org.arghyam.jalsoochak.user.repository.TenantConfigRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a tenant's default language: the first option of the chatbot's language menu, built from the
 * {@code LANGUAGE_SELECTION} screen of the consolidated {@code WHATSAPP_MESSAGE_TEMPLATES} tenant config.
 *
 * <p>The menu is the options for the tenant's {@code SUPPORTED_LANGUAGES}, in preference order, matched
 * through the language catalog. When no supported language has an option, or they cannot be read, the
 * default is {@code OPTION_1}, else the option with the lowest positive order.</p>
 *
 * <p>Fallback: {@code 1} for either id that cannot be resolved.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppPreferredLanguageService {

    public static final String CONFIG_KEY = "WHATSAPP_MESSAGE_TEMPLATES";
    private static final int FALLBACK_LANGUAGE_ID = 1;
    private static final PreferredLanguage FALLBACK =
            new PreferredLanguage(FALLBACK_LANGUAGE_ID, FALLBACK_LANGUAGE_ID);

    private final TenantConfigRepository tenantConfigRepository;
    private final LanguageCatalogRepository languageCatalogRepository;
    private final SupportedLanguageRepository supportedLanguageRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param languageId         the value stored in {@code user_table.language_id}: the option's order, or for
     *                           an option without a positive order, its position in the whole template list
     * @param whatsappLanguageId the language's catalog id, which the WhatsApp provider expects
     */
    public record PreferredLanguage(int languageId, int whatsappLanguageId) {
    }

    public PreferredLanguage resolvePreferredLanguage(Integer tenantId, String schemaName) {
        if (tenantId == null || tenantId <= 0) {
            return FALLBACK;
        }

        Optional<String> rawOpt = tenantConfigRepository.findConfigValue(tenantId, CONFIG_KEY);
        if (rawOpt.isEmpty() || rawOpt.get() == null || rawOpt.get().isBlank()) {
            return FALLBACK;
        }

        JsonNode options;
        try {
            options = languageOptions(objectMapper.readTree(rawOpt.get()));
        } catch (JsonProcessingException e) {
            log.warn("Invalid {} JSON for tenantId {}: {}", CONFIG_KEY, tenantId, e.getMessage());
            return FALLBACK;
        }
        if (options == null) {
            return FALLBACK;
        }

        Optional<PreferredLanguage> supported = firstSupportedLanguage(tenantId, schemaName, options);
        if (supported.isPresent()) {
            return supported.get();
        }
        JsonNode option = firstLanguageOption(options);
        if (option == null) {
            return FALLBACK;
        }
        return new PreferredLanguage(option.path("order").asInt(), resolveWhatsAppLanguageId(tenantId, option));
    }

    /** The {@code LANGUAGE_SELECTION} options object; null if absent. */
    private static JsonNode languageOptions(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode options = root.path("screens").path("LANGUAGE_SELECTION").path("options");
        if (options.isMissingNode() || !options.isObject()) {
            return null;
        }
        return options;
    }

    /**
     * The option of the most preferred supported language that has one, as the chatbot's menu orders them;
     * empty when none has, or they cannot be read. Of two options for one language, the lower order wins.
     */
    private Optional<PreferredLanguage> firstSupportedLanguage(Integer tenantId, String schemaName, JsonNode options) {
        try {
            List<String> supportedLanguages = supportedLanguageRepository.findLanguageNames(schemaName);
            if (supportedLanguages.isEmpty()) {
                return Optional.empty();
            }

            List<Map.Entry<String, JsonNode>> ordered = new ArrayList<>();
            options.fields().forEachRemaining(ordered::add);
            ordered.sort(Comparator
                    .comparingInt((Map.Entry<String, JsonNode> e) -> e.getValue().path("order").asInt(0))
                    .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));

            Map<Integer, PreferredLanguage> optionByCatalogId = new HashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                JsonNode option = ordered.get(i).getValue();
                int order = option.path("order").asInt(0);
                int languageId = order > 0 ? order : i + 1;
                languageCatalogRepository.findLanguageIdByAlias(canonicalLabel(option)).ifPresent(catalogId ->
                        optionByCatalogId.putIfAbsent(catalogId, new PreferredLanguage(languageId, catalogId)));
            }

            for (String language : supportedLanguages) {
                Optional<PreferredLanguage> match = languageCatalogRepository.findLanguageIdByAlias(language)
                        .map(optionByCatalogId::get);
                if (match.isPresent()) {
                    return match;
                }
            }
            return Optional.empty();
        } catch (DataAccessException e) {
            log.warn("Supported languages of tenantId {} could not be matched to {} options: {}",
                    tenantId, CONFIG_KEY, e.getMessage());
            return Optional.empty();
        }
    }

    /** The option's English label, else any non-blank label, as the chatbot names the option's language. */
    private static String canonicalLabel(JsonNode option) {
        JsonNode labels = option.path("label");
        String english = labels.path("en").asText("");
        if (!english.isBlank()) {
            return english;
        }
        Iterator<JsonNode> it = labels.elements();
        while (it.hasNext()) {
            String label = it.next().asText("");
            if (!label.isBlank()) {
                return label;
            }
        }
        return "";
    }

    /** OPTION_1 when its order is positive, else the option with the lowest positive order; null if none. */
    private static JsonNode firstLanguageOption(JsonNode options) {
        JsonNode option1 = options.path("OPTION_1");
        if (option1.path("order").asInt(0) > 0) {
            return option1;
        }

        JsonNode best = null;
        int bestOrder = Integer.MAX_VALUE;
        Iterator<Map.Entry<String, JsonNode>> it = options.fields();
        while (it.hasNext()) {
            JsonNode candidate = it.next().getValue();
            int order = candidate.path("order").asInt(0);
            if (order > 0 && order < bestOrder) {
                best = candidate;
                bestOrder = order;
            }
        }
        return best;
    }

    /** Looks the option's English label up in the language catalog. */
    private int resolveWhatsAppLanguageId(Integer tenantId, JsonNode option) {
        String language = option.path("label").path("en").asText("");
        if (language.isBlank()) {
            log.warn("{} language option for tenantId {} has no English label", CONFIG_KEY, tenantId);
            return FALLBACK_LANGUAGE_ID;
        }
        try {
            Optional<Integer> id = languageCatalogRepository.findLanguageIdByAlias(language);
            if (id.isEmpty()) {
                log.warn("Language '{}' of tenantId {} is not in the language catalog", language, tenantId);
            }
            return id.orElse(FALLBACK_LANGUAGE_ID);
        } catch (DataAccessException e) {
            log.warn("Language catalog lookup failed for tenantId {}: {}", tenantId, e.getMessage());
            return FALLBACK_LANGUAGE_ID;
        }
    }
}
