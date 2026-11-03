package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.telemetry.repository.SupportedLanguageRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The chatbot's language menu: the template's {@code LANGUAGE_SELECTION} options for the languages
 * the tenant supports ({@code SUPPORTED_LANGUAGES}), in the tenant's preference order.
 *
 * <p>Supported languages are matched to options through the language catalog, so the portal's name
 * and the option's English label need only be aliases of one language. A supported language with no
 * option is left off with a WARN. When the tenant supports no language with an option, or its
 * languages cannot be read, every option is offered.</p>
 *
 * <p>Each option keeps the id stored in {@code user_table.language_id}: its {@code order}, or for an
 * option without a positive order, its position in the whole template list.</p>
 */
@Service
@Slf4j
public class LanguageMenuService {

    private static final String SCREEN = "LANGUAGE_SELECTION";

    private final ConversationTemplateService templatesService;
    private final SupportedLanguageRepository supportedLanguageRepository;
    private final LanguageCatalogRepository languageCatalogRepository;

    public LanguageMenuService(ConversationTemplateService templatesService,
                               SupportedLanguageRepository supportedLanguageRepository,
                               LanguageCatalogRepository languageCatalogRepository) {
        this.templatesService = templatesService;
        this.supportedLanguageRepository = supportedLanguageRepository;
        this.languageCatalogRepository = languageCatalogRepository;
    }

    /**
     * The menu's options in display order; empty when the template has no {@code LANGUAGE_SELECTION}
     * options, so the caller falls back to the legacy {@code language_N} keys.
     */
    public List<MenuOption> resolveOptions(Integer tenantId, String schemaName) {
        List<ConversationTemplateService.TemplateOption> templateOptions =
                templatesService.resolveScreenOptions(tenantId, SCREEN);
        List<MenuOption> allOptions = new ArrayList<>(templateOptions.size());
        for (int i = 0; i < templateOptions.size(); i++) {
            ConversationTemplateService.TemplateOption option = templateOptions.get(i);
            allOptions.add(new MenuOption(option, option.order() > 0 ? option.order() : i + 1));
        }
        if (allOptions.isEmpty()) {
            return List.of();
        }

        List<String> supportedLanguages = findSupportedLanguages(tenantId, schemaName);
        if (supportedLanguages.isEmpty()) {
            return allOptions;
        }

        Map<Integer, MenuOption> optionByLanguageId = new HashMap<>();
        for (MenuOption option : allOptions) {
            languageCatalogRepository.findLanguageIdByAlias(option.option().canonicalLabel())
                    .ifPresent(languageId -> optionByLanguageId.putIfAbsent(languageId, option));
        }

        Map<Integer, MenuOption> menu = new LinkedHashMap<>();
        for (String language : supportedLanguages) {
            Optional<Integer> languageId = languageCatalogRepository.findLanguageIdByAlias(language);
            if (languageId.isEmpty()) {
                log.warn("Supported language '{}' of tenantId {} is not in the language catalog; left off the menu",
                        language, tenantId);
                continue;
            }
            MenuOption option = optionByLanguageId.get(languageId.get());
            if (option == null) {
                log.warn("Supported language '{}' of tenantId {} has no {} option; left off the menu",
                        language, tenantId, SCREEN);
                continue;
            }
            menu.putIfAbsent(languageId.get(), option);
        }
        if (menu.isEmpty()) {
            log.warn("No supported language of tenantId {} has a {} option; offering every option", tenantId, SCREEN);
            return allOptions;
        }
        return List.copyOf(menu.values());
    }

    private List<String> findSupportedLanguages(Integer tenantId, String schemaName) {
        try {
            return supportedLanguageRepository.findLanguageNames(schemaName);
        } catch (DataAccessException e) {
            log.warn("Supported languages of tenantId {} could not be read; offering every {} option: {}",
                    tenantId, SCREEN, e.getMessage());
            return List.of();
        }
    }

    /**
     * @param languageId the value stored in {@code user_table.language_id} when this option is chosen
     */
    public record MenuOption(ConversationTemplateService.TemplateOption option, int languageId) {
    }
}
