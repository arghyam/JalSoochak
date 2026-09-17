package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class WelcomeMessageService {

    private final GlificOperatorContextService operatorContextService;
    private final GlificLocalizationService localizationService;
    private final TenantConfigRepository tenantConfigRepository;
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    // Optional data-driven catalog (V36). When present it supplies locale codes for languages not in
    // the hardcoded switch below, so a new language needs only a DB row. Non-constructor field so the
    // Lombok @RequiredArgsConstructor signature (and its callers/tests) stay unchanged.
    @Autowired(required = false)
    private LanguageCatalogRepository languageCatalogRepository;

    public IntroResponse triggerWelcomeMessage(String phoneNumber, boolean isSingleTenant) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber/contactId is required");
        }

        TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(phoneNumber);
        Integer tenantId = operatorWithSchema.operator().tenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant could not be resolved for contact");
        }

        String name = safeName(operatorWithSchema.operator().title());
        String state = tenantConfigRepository.findConfigValue(tenantId, "state_name")
                .filter(v -> !v.isBlank())
                .or(() -> tenantConfigRepository.findTenantTitleById(tenantId))
                .orElse("");
        String language = operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId);
        String languageKey = localizationService.normalizeLanguageKey(language);

        String message = resolveWelcomeTemplate(tenantId, languageKey, language)
                .map(tpl -> tpl
                        .replace("{name}", name)
                        .replace("{state}", safeState(state))
                        .replace("{start_keyword}", resolveStartKeyword(isSingleTenant)))
                .orElseGet(() -> buildWelcomeMessage(languageKey, name, state, isSingleTenant));
        return IntroResponse.builder()
                .success(true)
                .correlationId(operatorWithSchema.operator().phoneNumber())
                .message(message)
                .build();
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "Operator";
        }
        return name.trim();
    }

    private String buildWelcomeMessage(String languageKey, String name, String state, boolean isSingleTenant) {
        String normalized = languageKey == null ? "english" : languageKey.toLowerCase(Locale.ROOT);
        String startKeyword = resolveStartKeyword(isSingleTenant);
        return switch (normalized) {
            case "hindi" -> """
                    प्रिय %s,

                    आपको जलसूचक में %s के लिए पंप ऑपरेटर के रूप में पंजीकृत किया गया है।

                    आप इस नंबर का उपयोग इन कार्यों के लिए कर सकते हैं:
                    - बल्क फ्लो मीटर की फोटो साझा करना
                    - जल आपूर्ति से जुड़ी समस्याएं रिपोर्ट करना

                    शुरू करने के लिए %s लिखें।
                    """.formatted(name, safeState(state), startKeyword);
            case "assamese", "as", "as_in" -> """
                    প্ৰিয় %s,

                    আপুনি জলোসূচকত %s ৰ পাম্প অপাৰেটৰ হিচাপে নথিভুক্ত হৈছে।

                    এই নম্বৰ ব্যৱহাৰ কৰি আপুনি:
                    - বাল্ক ফ্ল’ মিটাৰৰ ফটো পঠাব পাৰে
                    - পানী যোগানৰ সমস্যা জনাব পাৰে

                    আৰম্ভ কৰিবলৈ %s লিখক।
                    """.formatted(name, safeState(state), startKeyword);
            default -> """
                    Dear %s,

                    You have been registered as Pump Operator for %s in Jalsoochak.

                    You can use this number to:
                    - Share photos of the bulk flow meter
                    - Report water supply issues

                    Reply %s to begin.
                    """.formatted(name, safeState(state), startKeyword);
        };
    }

    private String resolveStartKeyword(boolean isSingleTenant) {
        return isSingleTenant ? "STARTTENANT" : "START";
    }

    /**
     * Tries tenant-configured welcome templates first so any supported language can be served
     * without code changes. Supported key patterns (first match wins):
     * - welcome_message_<normalized-language-key>
     * - welcome_message_<locale-code> (e.g. hi, ta, te, bn, mr)
     * - welcome_message_english
     * - welcome_message
     *
     * Template placeholders:
     * - {name}
     * - {state}
     */
    private Optional<String> resolveWelcomeTemplate(Integer tenantId, String languageKey, String rawLanguage) {
        ArrayList<String> candidates = new ArrayList<>();
        for (String key : languageCandidates(languageKey, rawLanguage)) {
            candidates.add("welcome_message_" + key);
        }
        candidates.add("welcome_message_english");
        candidates.add("welcome_message");
        for (String key : candidates) {
            Optional<String> value = tenantConfigRepository.findConfigValue(tenantId, key);
            if (value.isPresent() && value.get() != null && !value.get().isBlank()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Set<String> languageCandidates(String languageKey, String rawLanguage) {
        String norm = normalizeToken(languageKey);
        String rawNorm = normalizeToken(rawLanguage);
        String locale = mapToLocaleCode(norm, rawNorm);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!norm.isBlank()) out.add(norm);
        if (!rawNorm.isBlank()) out.add(rawNorm);
        if (!locale.isBlank()) out.add(locale);
        return out;
    }

    private String normalizeToken(String value) {
        if (value == null) return "";
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(lower).replaceAll("_").replaceAll("^_+|_+$", "");
    }

    // Package-private for unit testing of the resolution/fallback logic.
    String mapToLocaleCode(String normalizedKey, String normalizedRaw) {
        String key = !normalizedKey.isBlank() ? normalizedKey : normalizedRaw;
        if (languageCatalogRepository != null) {
            String fromCatalog = languageCatalogRepository.findLocaleCodeByAlias(key).orElse(null);
            if (fromCatalog != null && !fromCatalog.isBlank()) {
                return fromCatalog;
            }
        }
        return switch (key) {
            case "english", "en" -> "en";
            case "hindi", "hi" -> "hi";
            case "tamil", "ta" -> "ta";
            case "kannada", "kn" -> "kn";
            case "malayalam", "ml" -> "ml";
            case "telugu", "te" -> "te";
            case "odia", "oriya", "or" -> "or";
            case "assamese", "as", "as_in" -> "as";
            case "gujarati", "gu" -> "gu";
            case "bengali", "bn" -> "bn";
            case "punjabi", "pa" -> "pa";
            case "marathi", "mr" -> "mr";
            case "urdu", "ur" -> "ur";
            case "spanish", "es" -> "es";
            case "french", "fr" -> "fr";
            case "swahili", "sw" -> "sw";
            case "indonesian", "id" -> "id";
            case "kinyarwanda", "rw", "rw_rw" -> "rw";
            case "sign_language", "isl" -> "isl";
            case "gondi", "gon", "koitur" -> "gon";
            case "malay", "ms" -> "ms";
            default -> key.isBlank() ? "en" : key;
        };
    }

    private String safeState(String state) {
        if (state == null || state.isBlank()) {
            return "your state";
        }
        return state.trim();
    }
}
