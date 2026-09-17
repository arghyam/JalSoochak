package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Glific screen copy resolved from the consolidated {@code GLIFIC_MESSAGE_TEMPLATES} JSON blob.
 *
 * <p>Resolution always degrades rather than fails: a missing language falls back to English, then to
 * whatever translation exists, then to empty so the caller can use the legacy per-key configs.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificMessageTemplatesService")
class GlificMessageTemplatesServiceTest {

    private static final int TENANT = 17;

    private static final String TEMPLATES_JSON = """
            {
              "screens": {
                "INTRO_MESSAGE": {
                  "message": { "en": "Hello {name}", "hi": "नमस्ते {name}" },
                  "prompt": { "en": "Pick one" },
                  "confirmationTemplate": { "en": "Confirmed {reading}" }
                },
                "PLAIN_SCREEN": { "message": "A plain string message" },
                "ONLY_ASSAMESE": { "message": { "as": "নমস্কাৰ" } },
                "BLANK_ENGLISH": { "message": { "en": "  ", "hi": "नमस्ते" } },
                "ITEM_SELECTION": {
                  "options": {
                    "SUBMIT":  { "order": 2, "label": { "en": "Submit reading", "hi": "रीडिंग भेजें" } },
                    "REPORT":  { "order": 1, "label": { "en": "Report issue", "hi": "समस्या" } },
                    "UNLABELLED": { "order": 3 }
                  }
                },
                "ISSUE_REPORT": {
                  "reasons": {
                    "PUMP":  { "order": 1, "label": { "en": "Pump failure" } },
                    "POWER": { "order": 1, "label": { "en": "No electricity" } }
                  }
                },
                "BAD_OPTIONS": { "options": "not-an-object" }
              }
            }
            """;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private GlificMessageTemplatesService service;

    @BeforeEach
    void setUp() {
        service = new GlificMessageTemplatesService(tenantConfigRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "templatesCacheEnabled", true);
        ReflectionTestUtils.setField(service, "templatesCacheTtlMs", 120_000L);
        when(tenantConfigRepository.findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY))
                .thenReturn(Optional.of(TEMPLATES_JSON));
    }

    @Nested
    @DisplayName("loadTemplates")
    class LoadTemplates {

        @Test
        void isEmptyForAMissingTenant() {
            assertThat(service.loadTemplates(null)).isEmpty();
        }

        @Test
        void isEmptyWhenTheTenantHasNoTemplatesConfigured() {
            when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());

            assertThat(service.loadTemplates(TENANT)).isEmpty();
        }

        @Test
        void isEmptyWhenTheConfiguredJsonIsInvalid() {
            when(tenantConfigRepository.findConfigValue(anyInt(), anyString()))
                    .thenReturn(Optional.of("{ not json"));

            assertThat(service.loadTemplates(TENANT)).isEmpty();
        }

        @Test
        void isEmptyWhenTheConfiguredJsonIsALiteralNull() {
            when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.of("null"));

            assertThat(service.loadTemplates(TENANT)).isEmpty();
        }

        @Test
        void parsesTheConfiguredJson() {
            assertThat(service.loadTemplates(TENANT)).isPresent();
        }
    }

    @Nested
    @DisplayName("caching")
    class Caching {

        @Test
        void parsesTheTemplateBlobOncePerTenantWithinTheTtl() {
            service.loadTemplates(TENANT);
            service.loadTemplates(TENANT);

            verify(tenantConfigRepository, times(1))
                    .findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY);
        }

        @Test
        void reloadsAfterTheTenantCacheIsInvalidated() {
            service.loadTemplates(TENANT);
            service.invalidateTemplatesCache(TENANT);
            service.loadTemplates(TENANT);

            verify(tenantConfigRepository, times(2))
                    .findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY);
        }

        @Test
        void reloadsAfterTheWholeCacheIsInvalidated() {
            service.loadTemplates(TENANT);
            service.invalidateAllTemplatesCache();
            service.loadTemplates(TENANT);

            verify(tenantConfigRepository, times(2))
                    .findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY);
        }

        @Test
        void invalidatingANullTenantIsANoOp() {
            service.loadTemplates(TENANT);
            service.invalidateTemplatesCache(null);
            service.loadTemplates(TENANT);

            verify(tenantConfigRepository, times(1))
                    .findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY);
        }

        @Test
        void reloadsEveryTimeWhenCachingIsDisabled() {
            ReflectionTestUtils.setField(service, "templatesCacheEnabled", false);

            service.loadTemplates(TENANT);
            service.loadTemplates(TENANT);

            verify(tenantConfigRepository, times(2))
                    .findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY);
        }

        @Test
        void reloadsEveryTimeWhenTheTtlIsNonPositive() {
            ReflectionTestUtils.setField(service, "templatesCacheTtlMs", 0L);

            service.loadTemplates(TENANT);
            service.loadTemplates(TENANT);

            verify(tenantConfigRepository, times(2))
                    .findConfigValue(TENANT, GlificMessageTemplatesService.CONFIG_KEY);
        }
    }

    @Nested
    @DisplayName("screen text resolution")
    class ScreenText {

        @Test
        void resolvesTheRequestedLanguage() {
            assertThat(service.resolveScreenMessage(TENANT, "INTRO_MESSAGE", "hindi"))
                    .contains("नमस्ते {name}");
        }

        @Test
        void resolvesEnglishByDefault() {
            assertThat(service.resolveScreenMessage(TENANT, "INTRO_MESSAGE", "english"))
                    .contains("Hello {name}");
        }

        @Test
        void fallsBackToEnglishForAnUnmappedLanguage() {
            assertThat(service.resolveScreenMessage(TENANT, "INTRO_MESSAGE", "assamese"))
                    .contains("Hello {name}");
        }

        @Test
        void fallsBackToTheFirstAvailableTranslationWhenEnglishIsMissing() {
            assertThat(service.resolveScreenMessage(TENANT, "ONLY_ASSAMESE", "english"))
                    .contains("নমস্কাৰ");
        }

        @Test
        void treatsABlankEnglishValueAsMissing() {
            assertThat(service.resolveScreenMessage(TENANT, "BLANK_ENGLISH", "english"))
                    .contains("नमस्ते");
        }

        @Test
        void acceptsAPlainStringInsteadOfALanguageMap() {
            assertThat(service.resolveScreenMessage(TENANT, "PLAIN_SCREEN", "hindi"))
                    .contains("A plain string message");
        }

        @Test
        void isEmptyForAnUnknownScreenOrField() {
            assertThat(service.resolveScreenMessage(TENANT, "NO_SUCH_SCREEN", "english")).isEmpty();
            assertThat(service.resolveScreenText(TENANT, "INTRO_MESSAGE", "noSuchField", "english")).isEmpty();
        }

        @Test
        void isEmptyWhenTheTenantHasNoTemplates() {
            when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());

            assertThat(service.resolveScreenMessage(TENANT, "INTRO_MESSAGE", "english")).isEmpty();
        }

        @Test
        void resolvesPromptsAndConfirmationTemplatesThroughTheSamePath() {
            assertThat(service.resolveScreenPrompt(TENANT, "INTRO_MESSAGE", "english")).contains("Pick one");
            assertThat(service.resolveScreenConfirmationTemplate(TENANT, "INTRO_MESSAGE", "english"))
                    .contains("Confirmed {reading}");
        }
    }

    @Nested
    @DisplayName("option and reason lists")
    class OptionLists {

        @Test
        void returnsOptionsInDeclaredOrder() {
            List<GlificMessageTemplatesService.TemplateOption> options =
                    service.resolveScreenOptions(TENANT, "ITEM_SELECTION");

            assertThat(options).extracting(GlificMessageTemplatesService.TemplateOption::key)
                    .containsExactly("REPORT", "SUBMIT", "UNLABELLED");
        }

        @Test
        void breaksAnOrderTieByKeyCaseInsensitively() {
            List<GlificMessageTemplatesService.TemplateOption> reasons =
                    service.resolveScreenReasons(TENANT, "ISSUE_REPORT");

            assertThat(reasons).extracting(GlificMessageTemplatesService.TemplateOption::key)
                    .containsExactly("POWER", "PUMP");
        }

        @Test
        void returnsAnEmptyListForAMissingOrMalformedContainer() {
            assertThat(service.resolveScreenOptions(TENANT, "NO_SUCH_SCREEN")).isEmpty();
            assertThat(service.resolveScreenOptions(TENANT, "BAD_OPTIONS")).isEmpty();
            assertThat(service.resolveScreenReasons(TENANT, "ITEM_SELECTION")).isEmpty();
        }

        @Test
        void returnsAnEmptyListWhenTheTenantHasNoTemplates() {
            when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());

            assertThat(service.resolveScreenOptions(TENANT, "ITEM_SELECTION")).isEmpty();
        }

        @Test
        void defaultsAMissingOrderToZero() {
            assertThat(service.resolveScreenOptions(TENANT, "ITEM_SELECTION"))
                    .filteredOn(o -> "UNLABELLED".equals(o.key()))
                    .singleElement()
                    .satisfies(o -> assertThat(o.labelByLang()).isEmpty());
        }
    }

    @Nested
    @DisplayName("TemplateOption")
    class TemplateOptionBehaviour {

        private GlificMessageTemplatesService.TemplateOption option(Map<String, String> labels) {
            return new GlificMessageTemplatesService.TemplateOption("KEY", 1, labels);
        }

        @Test
        void labelForLanguageKeyPrefersTheRequestedLanguage() {
            assertThat(option(Map.of("en", "Submit", "hi", "भेजें")).labelForLanguageKey("hindi"))
                    .isEqualTo("भेजें");
        }

        @Test
        void labelForLanguageKeyFallsBackToEnglish() {
            assertThat(option(Map.of("en", "Submit")).labelForLanguageKey("hindi")).isEqualTo("Submit");
        }

        @Test
        void labelForLanguageKeyFallsBackToAnyNonBlankTranslation() {
            assertThat(option(Map.of("as", "জমা")).labelForLanguageKey("hindi")).isEqualTo("জমা");
        }

        @Test
        void labelForLanguageKeyIsNullWhenNoTranslationIsUsable() {
            assertThat(option(Map.of()).labelForLanguageKey("hindi")).isNull();
            assertThat(option(Map.of("en", "   ")).labelForLanguageKey("hindi")).isNull();
        }

        @Test
        void canonicalLabelPrefersEnglish() {
            assertThat(option(Map.of("en", "Submit", "hi", "भेजें")).canonicalLabel()).isEqualTo("Submit");
        }

        @Test
        void canonicalLabelFallsBackWhenEnglishIsMissing() {
            assertThat(option(Map.of("hi", "भेजें")).canonicalLabel()).isEqualTo("भेजें");
        }

        @Test
        void matchesAnyLabelIsCaseInsensitiveAcrossEveryTranslation() {
            var opt = option(Map.of("en", "Submit reading", "hi", "रीडिंग भेजें"));

            assertThat(opt.matchesAnyLabel("submit reading")).isTrue();
            assertThat(opt.matchesAnyLabel("  SUBMIT READING  ")).isTrue();
            assertThat(opt.matchesAnyLabel("रीडिंग भेजें")).isTrue();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   ", "something else"})
        void matchesAnyLabelRejectsBlankAndUnknownInput(String input) {
            assertThat(option(Map.of("en", "Submit reading")).matchesAnyLabel(input)).isFalse();
        }
    }

    @Nested
    @DisplayName("language code mapping")
    class LanguageCodes {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                "hindi,hi",
                "hi,hi",
                "HINDI,hi",
                "'  Hindi  ',hi",
                "english,en",
                "en,en",
                "ENGLISH,en",
                "assamese,en",
                "'',en"
        })
        void mapsNormalizedLanguageKeysToTemplateCodes(String languageKey, String expected) {
            assertThat(GlificMessageTemplatesService.toTemplateLanguageCode(languageKey)).isEqualTo(expected);
        }

        @Test
        void defaultsToEnglishForANullKey() {
            assertThat(GlificMessageTemplatesService.toTemplateLanguageCode(null)).isEqualTo("en");
        }
    }
}
