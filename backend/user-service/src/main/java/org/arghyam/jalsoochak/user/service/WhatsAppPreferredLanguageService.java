package org.arghyam.jalsoochak.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.user.repository.TenantConfigRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the default/preferred language for a tenant from the first option of the
 * {@code LANGUAGE_SELECTION} screen in the consolidated {@code WHATSAPP_MESSAGE_TEMPLATES} tenant config.
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
    private final ObjectMapper objectMapper;

    /**
     * @param languageId         the option's position in the tenant's language list, as stored in
     *                           {@code user_table.language_id}
     * @param whatsappLanguageId the language's catalog id, which the WhatsApp provider expects
     */
    public record PreferredLanguage(int languageId, int whatsappLanguageId) {
    }

    public PreferredLanguage resolvePreferredLanguage(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return FALLBACK;
        }

        Optional<String> rawOpt = tenantConfigRepository.findConfigValue(tenantId, CONFIG_KEY);
        if (rawOpt.isEmpty() || rawOpt.get() == null || rawOpt.get().isBlank()) {
            return FALLBACK;
        }

        JsonNode option;
        try {
            option = firstLanguageOption(objectMapper.readTree(rawOpt.get()));
        } catch (JsonProcessingException e) {
            log.warn("Invalid {} JSON for tenantId {}: {}", CONFIG_KEY, tenantId, e.getMessage());
            return FALLBACK;
        }
        if (option == null) {
            return FALLBACK;
        }
        return new PreferredLanguage(option.path("order").asInt(), resolveWhatsAppLanguageId(tenantId, option));
    }

    /** OPTION_1 when its order is positive, else the option with the lowest positive order; null if none. */
    private static JsonNode firstLanguageOption(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode options = root.path("screens").path("LANGUAGE_SELECTION").path("options");
        if (options.isMissingNode() || !options.isObject()) {
            return null;
        }

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
