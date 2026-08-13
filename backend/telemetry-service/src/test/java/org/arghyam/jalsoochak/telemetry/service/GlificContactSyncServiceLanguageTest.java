package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlificContactSyncServiceLanguageTest {

    private final GlificContactSyncService service = new GlificContactSyncService(null, null);

    private void withCatalog(LanguageCatalogRepository repo) {
        ReflectionTestUtils.setField(service, "languageCatalogRepository", repo);
    }

    @Test
    void fallsBackToHardcodedCatalogWhenRepositoryAbsent() {
        withCatalog(null);
        assertEquals(8, service.resolveGlificLanguageId("Assamese"));
        assertEquals(2, service.resolveGlificLanguageId("hindi"));
        assertEquals(1, service.resolveGlificLanguageId("en"));
    }

    @Test
    void numericLanguageIsPassedThroughBeforeCatalog() {
        withCatalog(mock(LanguageCatalogRepository.class));
        assertEquals(5, service.resolveGlificLanguageId("5"));
    }

    @Test
    void catalogResolvesNewLanguageWithoutCodeChange() {
        LanguageCatalogRepository repo = mock(LanguageCatalogRepository.class);
        when(repo.findLanguageIdByAlias("klingon")).thenReturn(Optional.of(99));
        withCatalog(repo);

        assertEquals(99, service.resolveGlificLanguageId("Klingon"));
    }

    @Test
    void fallsBackToHardcodedWhenCatalogHasNoRow() {
        LanguageCatalogRepository repo = mock(LanguageCatalogRepository.class);
        when(repo.findLanguageIdByAlias("hindi")).thenReturn(Optional.empty());
        withCatalog(repo);

        assertEquals(2, service.resolveGlificLanguageId("hindi"));
    }

    @Test
    void blankOrUnknownLanguageReturnsNull() {
        withCatalog(null);
        assertNull(service.resolveGlificLanguageId(null));
        assertNull(service.resolveGlificLanguageId("   "));
        assertNull(service.resolveGlificLanguageId("no-such-language"));
    }
}
