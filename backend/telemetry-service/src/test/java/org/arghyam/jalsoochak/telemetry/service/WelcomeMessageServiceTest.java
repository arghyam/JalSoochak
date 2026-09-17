package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The one-time welcome message sent when an operator is first registered. Copy comes from a
 * tenant-configured template when one exists (so a new language needs no code change) and otherwise
 * from the built-in English/Hindi/Assamese bodies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WelcomeMessageService")
class WelcomeMessageServiceTest {

    private static final String PHONE = "919999900001";
    private static final String SCHEMA = "tenant_as";

    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private GlificLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @InjectMocks
    private WelcomeMessageService service;

    private static TelemetryOperatorWithSchema operator(Integer tenantId, String title) {
        return new TelemetryOperatorWithSchema(SCHEMA,
                new TelemetryOperator(11L, tenantId, title, "a@b.c", PHONE, 1));
    }

    @BeforeEach
    void stubDefaults() {
        when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator(3, "Asha"));
        when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("English");
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("english");
        when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());
        when(tenantConfigRepository.findTenantTitleById(anyInt())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        void rejectsAMissingPhoneNumber() {
            assertThatThrownBy(() -> service.triggerWelcomeMessage(null, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("phoneNumber/contactId is required");
            assertThatThrownBy(() -> service.triggerWelcomeMessage("  ", false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAnOperatorWithoutAResolvableTenant() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator(null, "Asha"));

            assertThatThrownBy(() -> service.triggerWelcomeMessage(PHONE, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Tenant could not be resolved");
        }

        @Test
        void propagatesAFailedOperatorLookup() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE))
                    .thenThrow(new IllegalStateException("No operator found"));

            assertThatThrownBy(() -> service.triggerWelcomeMessage(PHONE, false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("tenant-configured template")
    class ConfiguredTemplate {

        @Test
        void prefersTheLanguageScopedTemplateAndFillsEveryPlaceholder() {
            when(tenantConfigRepository.findConfigValue(3, "welcome_message_english"))
                    .thenReturn(Optional.of("Hi {name} of {state}, send {start_keyword}."));
            when(tenantConfigRepository.findConfigValue(3, "state_name")).thenReturn(Optional.of("Assam"));

            var response = service.triggerWelcomeMessage(PHONE, false);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("Hi Asha of Assam, send START.");
            assertThat(response.getCorrelationId()).isEqualTo(PHONE);
        }

        @Test
        void usesTheSingleTenantStartKeywordWhenAsked() {
            when(tenantConfigRepository.findConfigValue(3, "welcome_message_english"))
                    .thenReturn(Optional.of("Send {start_keyword}."));

            assertThat(service.triggerWelcomeMessage(PHONE, true).getMessage())
                    .isEqualTo("Send STARTTENANT.");
        }

        @Test
        void looksUpTheLocaleCodeKeyBeforeFallingBackToEnglish() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("assamese");
            when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("Assamese");
            when(tenantConfigRepository.findConfigValue(3, "welcome_message_as"))
                    .thenReturn(Optional.of("Assamese locale template"));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage())
                    .isEqualTo("Assamese locale template");
        }

        @Test
        void fallsBackToTheUnscopedWelcomeMessageKey() {
            when(tenantConfigRepository.findConfigValue(3, "welcome_message"))
                    .thenReturn(Optional.of("Generic welcome"));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).isEqualTo("Generic welcome");
        }

        @Test
        void skipsABlankConfiguredTemplate() {
            when(tenantConfigRepository.findConfigValue(3, "welcome_message_english"))
                    .thenReturn(Optional.of("   "));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage())
                    .contains("You have been registered as Pump Operator");
        }
    }

    @Nested
    @DisplayName("built-in templates")
    class BuiltInTemplates {

        @Test
        void rendersTheEnglishBodyByDefault() {
            var response = service.triggerWelcomeMessage(PHONE, false);

            assertThat(response.getMessage())
                    .contains("Dear Asha")
                    .contains("You have been registered as Pump Operator")
                    .contains("Reply START to begin.");
        }

        @Test
        void rendersTheHindiBody() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage())
                    .contains("प्रिय Asha")
                    .contains("पंप ऑपरेटर");
        }

        @ParameterizedTest(name = "languageKey={0} renders the Assamese body")
        @CsvSource({"assamese", "as", "as_in"})
        void rendersTheAssameseBodyForEveryAssameseKey(String languageKey) {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn(languageKey);

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage())
                    .contains("প্ৰিয় Asha")
                    .contains("পাম্প অপাৰেটৰ");
        }

        @Test
        void fallsBackToTheEnglishBodyForAnUnsupportedLanguage() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("klingon");

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Dear Asha");
        }

        @Test
        void fallsBackToTheEnglishBodyForANullLanguageKey() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn(null);

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Dear Asha");
        }

        @Test
        void usesTheSingleTenantStartKeywordInTheBuiltInBody() {
            assertThat(service.triggerWelcomeMessage(PHONE, true).getMessage())
                    .contains("Reply STARTTENANT to begin.");
        }
    }

    @Nested
    @DisplayName("name and state substitution")
    class Substitution {

        @Test
        void substitutesOperatorWhenTheNameIsMissing() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator(3, "  "));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Dear Operator");
        }

        @Test
        void substitutesOperatorWhenTheNameIsNull() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator(3, null));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Dear Operator");
        }

        @Test
        void trimsTheOperatorName() {
            when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operator(3, "  Asha  "));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Dear Asha,");
        }

        @Test
        void prefersTheConfiguredStateNameOverTheTenantTitle() {
            when(tenantConfigRepository.findConfigValue(3, "state_name")).thenReturn(Optional.of("Assam"));
            when(tenantConfigRepository.findTenantTitleById(3)).thenReturn(Optional.of("Assam PHED"));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage())
                    .contains("Pump Operator for Assam in Jalsoochak");
        }

        @Test
        void fallsBackToTheTenantTitleWhenNoStateNameIsConfigured() {
            when(tenantConfigRepository.findConfigValue(eq(3), eq("state_name"))).thenReturn(Optional.empty());
            when(tenantConfigRepository.findTenantTitleById(3)).thenReturn(Optional.of("Assam PHED"));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Assam PHED");
        }

        @Test
        void fallsBackToTheTenantTitleWhenTheConfiguredStateNameIsBlank() {
            when(tenantConfigRepository.findConfigValue(eq(3), eq("state_name"))).thenReturn(Optional.of("  "));
            when(tenantConfigRepository.findTenantTitleById(3)).thenReturn(Optional.of("Assam PHED"));

            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage()).contains("Assam PHED");
        }

        @Test
        void substitutesYourStateWhenNeitherIsConfigured() {
            assertThat(service.triggerWelcomeMessage(PHONE, false).getMessage())
                    .contains("Pump Operator for your state in Jalsoochak");
        }
    }
}
