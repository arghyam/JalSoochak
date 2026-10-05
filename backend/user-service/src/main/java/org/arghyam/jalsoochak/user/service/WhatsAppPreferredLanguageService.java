package org.arghyam.jalsoochak.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.repository.TenantConfigRepository;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the default/preferred language_id for a tenant based on the consolidated
 * {@code WHATSAPP_MESSAGE_TEMPLATES} tenant config JSON.
 *
 * <p>Fallback: returns {@code 1} if the config is missing/invalid.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppPreferredLanguageService {

    public static final String CONFIG_KEY = "WHATSAPP_MESSAGE_TEMPLATES";
    private static final int FALLBACK_LANGUAGE_ID = 1;

    private final TenantConfigRepository tenantConfigRepository;
    private final ObjectMapper objectMapper;

    public int resolvePreferredLanguageId(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return FALLBACK_LANGUAGE_ID;
        }

        Optional<String> rawOpt = tenantConfigRepository.findConfigValue(tenantId, CONFIG_KEY);
        if (rawOpt.isEmpty() || rawOpt.get() == null || rawOpt.get().isBlank()) {
            return FALLBACK_LANGUAGE_ID;
        }

        try {
            JsonNode root = objectMapper.readTree(rawOpt.get());
            if (root == null || root.isNull()) {
                return FALLBACK_LANGUAGE_ID;
            }

            int explicit = root.path("preferredLanguageId").asInt(0);
            if (explicit <= 0) {
                explicit = root.path("defaultLanguageId").asInt(0);
            }
            if (explicit > 0) {
                return explicit;
            }

            JsonNode options = root.path("screens").path("LANGUAGE_SELECTION").path("options");
            if (options.isMissingNode() || !options.isObject()) {
                return FALLBACK_LANGUAGE_ID;
            }

            int option1 = options.path("OPTION_1").path("order").asInt(0);
            if (option1 > 0) {
                return option1;
            }

            int best = Integer.MAX_VALUE;
            Iterator<Map.Entry<String, JsonNode>> it = options.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                int order = entry.getValue().path("order").asInt(0);
                if (order > 0 && order < best) {
                    best = order;
                }
            }
            return best == Integer.MAX_VALUE ? FALLBACK_LANGUAGE_ID : best;
        } catch (Exception e) {
            log.warn("Invalid {} JSON for tenantId {}: {}", CONFIG_KEY, tenantId, e.getMessage());
            return FALLBACK_LANGUAGE_ID;
        }
    }
}

