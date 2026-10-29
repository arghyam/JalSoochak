package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanguageLocaleResolverTest {

    @Mock
    private LanguageCatalogRepository languageCatalogRepository;

    private LanguageLocaleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new LanguageLocaleResolver(languageCatalogRepository);
        lenient().when(languageCatalogRepository.findLocaleCodeByAlias(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void prefersTheCatalogSoANewLanguageNeedsNoCodeChange() {
        when(languageCatalogRepository.findLocaleCodeByAlias("klingon")).thenReturn(Optional.of("tlh"));

        assertThat(resolver.localeCodeFor("Klingon")).isEqualTo("tlh");
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "english,en",
            "hindi,hi",
            "punjabi,pa",
            "assamese,as",
            "oriya,or",
            "'  Hindi  ',hi",
            "AS_IN,as",
            "as-in,as",
            "pa,pa",
    })
    void fallsBackToTheBuiltInListWhenTheCatalogHasNoRow(String language, String expected) {
        assertThat(resolver.localeCodeFor(language)).isEqualTo(expected);
    }

    @Test
    void ignoresABlankCatalogCode() {
        when(languageCatalogRepository.findLocaleCodeByAlias("hindi")).thenReturn(Optional.of(" "));

        assertThat(resolver.localeCodeFor("hindi")).isEqualTo("hi");
    }

    @Test
    void returnsAnUnknownLanguageAsItself() {
        assertThat(resolver.localeCodeFor("klingon")).isEqualTo("klingon");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "--"})
    void isEnglishForABlankLanguageWithoutAskingTheCatalog(String language) {
        assertThat(resolver.localeCodeFor(language)).isEqualTo("en");
        verifyNoInteractions(languageCatalogRepository);
    }
}
