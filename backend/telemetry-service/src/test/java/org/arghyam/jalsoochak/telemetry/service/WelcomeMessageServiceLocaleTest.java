package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WelcomeMessageServiceLocaleTest {

    private final WelcomeMessageService service = new WelcomeMessageService(null, null, null);

    private void withCatalog(LanguageCatalogRepository repo) {
        ReflectionTestUtils.setField(service, "languageCatalogRepository", repo);
    }

    @Test
    void fallsBackToHardcodedSwitchWhenRepositoryAbsent() {
        withCatalog(null);
        assertEquals("hi", service.mapToLocaleCode("hindi", ""));
        assertEquals("as", service.mapToLocaleCode("assamese", ""));
        assertEquals("or", service.mapToLocaleCode("oriya", ""));
    }

    @Test
    void unknownLanguageFallsBackToKeyItself() {
        withCatalog(null);
        // Preserves the existing switch default: unknown key echoes back as its own locale code.
        assertEquals("klingon", service.mapToLocaleCode("klingon", ""));
    }

    @Test
    void catalogSuppliesLocaleForNewLanguageWithoutCodeChange() {
        LanguageCatalogRepository repo = mock(LanguageCatalogRepository.class);
        when(repo.findLocaleCodeByAlias("klingon")).thenReturn(Optional.of("tlh"));
        withCatalog(repo);

        assertEquals("tlh", service.mapToLocaleCode("klingon", ""));
    }

    @Test
    void fallsBackToSwitchWhenCatalogHasNoRow() {
        LanguageCatalogRepository repo = mock(LanguageCatalogRepository.class);
        when(repo.findLocaleCodeByAlias("hindi")).thenReturn(Optional.empty());
        withCatalog(repo);

        assertEquals("hi", service.mapToLocaleCode("hindi", ""));
    }
}
