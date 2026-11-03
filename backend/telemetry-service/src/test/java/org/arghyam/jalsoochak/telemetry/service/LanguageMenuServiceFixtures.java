package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.telemetry.repository.SupportedLanguageRepository;

import static org.mockito.Mockito.mock;

/** Builds a {@link LanguageMenuService} for service tests that don't exercise {@code SUPPORTED_LANGUAGES}. */
public final class LanguageMenuServiceFixtures {

    private LanguageMenuServiceFixtures() {
    }

    /** Offers every template option, as for a tenant that supports no language. */
    public static LanguageMenuService unfiltered(ConversationTemplateService templatesService) {
        return new LanguageMenuService(templatesService,
                mock(SupportedLanguageRepository.class), mock(LanguageCatalogRepository.class));
    }
}
