package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
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

    private final OperatorContextService operatorContextService;
    private final ConversationLocalizationService localizationService;
    private final TenantConfigRepository tenantConfigRepository;
    private final LanguageLocaleResolver languageLocaleResolver;
    private final ConversationMessageResolver messageResolver;
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

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
        return messageResolver.get(ConversationMessageCode.WELCOME, languageKey,
                name, safeState(state), resolveStartKeyword(isSingleTenant));
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
        String locale = languageLocaleResolver.localeCodeFor(!norm.isBlank() ? norm : rawNorm);
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

    private String safeState(String state) {
        if (state == null || state.isBlank()) {
            return "your state";
        }
        return state.trim();
    }
}
