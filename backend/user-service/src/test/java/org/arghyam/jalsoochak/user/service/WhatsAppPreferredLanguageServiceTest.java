package org.arghyam.jalsoochak.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.user.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.user.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.user.service.WhatsAppPreferredLanguageService.PreferredLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WhatsAppPreferredLanguageService")
class WhatsAppPreferredLanguageServiceTest {

    private static final PreferredLanguage FALLBACK = new PreferredLanguage(1, 1);

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @Mock
    private LanguageCatalogRepository languageCatalogRepository;

    private WhatsAppPreferredLanguageService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppPreferredLanguageService(
                tenantConfigRepository, languageCatalogRepository, new ObjectMapper());
    }

    private void givenTemplates(String json) {
        when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                .thenReturn(Optional.of(json));
    }

    private static String options(String options) {
        return "{\"screens\":{\"LANGUAGE_SELECTION\":{\"options\":{" + options + "}}}}";
    }

    @Nested
    @DisplayName("fallback cases")
    class Fallback {

        @Test
        @DisplayName("returns 1 for null tenantId")
        void nullTenantId() {
            assertThat(service.resolvePreferredLanguage(null)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("returns 1 for zero tenantId")
        void zeroTenantId() {
            assertThat(service.resolvePreferredLanguage(0)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("returns 1 for negative tenantId")
        void negativeTenantId() {
            assertThat(service.resolvePreferredLanguage(-1)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("returns 1 when config is absent, without reading any other key")
        void configAbsent() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.empty());
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
            verify(tenantConfigRepository).findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY);
            verifyNoMoreInteractions(tenantConfigRepository);
            verifyNoInteractions(languageCatalogRepository);
        }

        @Test
        @DisplayName("returns 1 when config value is blank")
        void configBlank() {
            givenTemplates("  ");
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("returns 1 for invalid JSON")
        void invalidJson() {
            givenTemplates("not-json");
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("returns 1 when JSON has no language fields")
        void noLanguageFields() {
            givenTemplates("{\"foo\":\"bar\"}");
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("ignores top-level preferredLanguageId and defaultLanguageId")
        void ignoresExplicitIds() {
            givenTemplates("{\"preferredLanguageId\":3,\"defaultLanguageId\":5}");
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
        }
    }

    @Nested
    @DisplayName("LANGUAGE_SELECTION screen options")
    class LanguageSelectionOptions {

        @Test
        @DisplayName("returns OPTION_1's position and its language's catalog id")
        void returnsOption1PositionAndCatalogId() {
            givenTemplates(options("""
                    "OPTION_1":{"order":1,"label":{"en":"Punjabi","pa":"ਪੰਜਾਬੀ"}},
                    "OPTION_2":{"order":2,"label":{"en":"English"}}"""));
            when(languageCatalogRepository.findLanguageIdByAlias("Punjabi")).thenReturn(Optional.of(11));

            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(new PreferredLanguage(1, 11));
        }

        @Test
        @DisplayName("returns OPTION_1 even when another option has a lower order")
        void prefersOption1() {
            givenTemplates(options("""
                    "OPTION_1":{"order":2,"label":{"en":"Hindi"}},
                    "OPTION_2":{"order":1,"label":{"en":"English"}}"""));
            when(languageCatalogRepository.findLanguageIdByAlias("Hindi")).thenReturn(Optional.of(2));

            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(new PreferredLanguage(2, 2));
        }

        @Test
        @DisplayName("returns the lowest-order option when OPTION_1 has order 0")
        void returnsLowestOrderWhenOption1IsZero() {
            givenTemplates(options("""
                    "OPTION_1":{"order":0,"label":{"en":"English"}},
                    "OPTION_2":{"order":3,"label":{"en":"Hindi"}},
                    "OPTION_3":{"order":2,"label":{"en":"Assamese"}}"""));
            when(languageCatalogRepository.findLanguageIdByAlias("Assamese")).thenReturn(Optional.of(8));

            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(new PreferredLanguage(2, 8));
        }

        @Test
        @DisplayName("returns fallback 1 when options object is absent")
        void fallbackWhenOptionsAbsent() {
            givenTemplates("{\"screens\":{\"LANGUAGE_SELECTION\":{}}}");
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("returns fallback 1 when no option has a positive order")
        void fallbackWhenNoPositiveOrder() {
            givenTemplates(options("\"OPTION_1\":{\"order\":0,\"label\":{\"en\":\"Hindi\"}}"));
            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(FALLBACK);
            verifyNoInteractions(languageCatalogRepository);
        }
    }

    @Nested
    @DisplayName("catalog id fallback")
    class CatalogIdFallback {

        @Test
        @DisplayName("keeps the position and sends 1 when the language is not in the catalog")
        void languageNotInCatalog() {
            givenTemplates(options("\"OPTION_1\":{\"order\":2,\"label\":{\"en\":\"Klingon\"}}"));
            when(languageCatalogRepository.findLanguageIdByAlias("Klingon")).thenReturn(Optional.empty());

            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(new PreferredLanguage(2, 1));
        }

        @Test
        @DisplayName("keeps the position and sends 1 when the option has no English label")
        void noEnglishLabel() {
            givenTemplates(options("\"OPTION_1\":{\"order\":2,\"label\":{\"hi\":\"हिंदी\"}}"));

            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(new PreferredLanguage(2, 1));
            verifyNoInteractions(languageCatalogRepository);
        }

        @Test
        @DisplayName("keeps the position and sends 1 when the catalog lookup fails")
        void catalogLookupFails() {
            givenTemplates(options("\"OPTION_1\":{\"order\":2,\"label\":{\"en\":\"Hindi\"}}"));
            when(languageCatalogRepository.findLanguageIdByAlias("Hindi"))
                    .thenThrow(new DataAccessResourceFailureException("db down"));

            assertThat(service.resolvePreferredLanguage(1)).isEqualTo(new PreferredLanguage(2, 1));
        }
    }
}
