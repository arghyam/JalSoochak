package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Intro and closing WhatsApp copy: template resolution (new screen-message table first, legacy
 * per-language config keys as fallback), {name} substitution, and the catch-all that keeps a
 * misconfigured tenant from surfacing a stack trace to the operator.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificMessageService")
class GlificMessageServiceTest {

    private static final String CONTACT = "919999900001";
    private static final String SCHEMA = "tenant_as";
    private static final String FALLBACK = "Something went wrong. Please try again.";

    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private GlificLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificMessageTemplatesService templatesService;

    @InjectMocks
    private GlificMessageService service;

    private static TelemetryOperatorWithSchema operator(Integer tenantId, String title) {
        return new TelemetryOperatorWithSchema(SCHEMA,
                new TelemetryOperator(11L, tenantId, title, "a@b.c", CONTACT, 1));
    }

    private static IntroRequest introRequest(String contactId) {
        IntroRequest request = new IntroRequest();
        request.setContactId(contactId);
        return request;
    }

    private static ClosingRequest closingRequest(String contactId) {
        ClosingRequest request = new ClosingRequest();
        request.setContactId(contactId);
        return request;
    }

    @BeforeEach
    void stubLanguageChain() {
        when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(3, "Asha"));
        when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("English");
        when(localizationService.normalizeLanguageKey("English")).thenReturn("english");
    }

    @Nested
    @DisplayName("introMessage")
    class Intro {

        @Test
        void rendersTheScreenMessageTemplateWithTheOperatorName() {
            when(templatesService.resolveScreenMessage(3, "INTRO_MESSAGE", "english"))
                    .thenReturn(Optional.of("Hello {name}, welcome."));

            var response = service.introMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("Hello Asha, welcome.");
        }

        @Test
        void substitutesThereWhenTheOperatorHasNoName() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(3, "  "));
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.of("Hello {name}."));

            assertThat(service.introMessage(introRequest(CONTACT)).getMessage()).isEqualTo("Hello there.");
        }

        @Test
        void substitutesThereWhenTheOperatorNameIsNull() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(3, null));
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.of("Hello {name}."));

            assertThat(service.introMessage(introRequest(CONTACT)).getMessage()).isEqualTo("Hello there.");
        }

        @Test
        void fallsBackToTheLegacyLanguageScopedConfigKey() {
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "intro_message_english"))
                    .thenReturn(Optional.of("Legacy hello {name}."));

            assertThat(service.introMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Legacy hello Asha.");
        }

        @Test
        void fallsBackToTheUnscopedLegacyKeyForEnglish() {
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "intro_message_english")).thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "intro_message"))
                    .thenReturn(Optional.of("Generic hello {name}."));

            assertThat(service.introMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Generic hello Asha.");
        }

        @Test
        void doesNotFallBackToTheUnscopedKeyForANonEnglishLanguage() {
            when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("Assamese");
            when(localizationService.normalizeLanguageKey("Assamese")).thenReturn("assamese");
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "intro_message_assamese")).thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "intro_message"))
                    .thenReturn(Optional.of("Generic hello {name}."));

            // Silently serving English copy to an Assamese operator would be worse than the error path.
            var response = service.introMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo(FALLBACK);
        }

        @Test
        void returnsTheGenericFallbackWhenNoTemplateIsConfigured() {
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());

            var response = service.introMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo(FALLBACK);
        }

        @Test
        void returnsTheGenericFallbackForAMissingContactId() {
            assertThat(service.introMessage(introRequest(null)).isSuccess()).isFalse();
            assertThat(service.introMessage(introRequest("  ")).getMessage()).isEqualTo(FALLBACK);
        }

        @Test
        void returnsTheGenericFallbackWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null, "Asha"));

            assertThat(service.introMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void returnsTheGenericFallbackWhenOperatorResolutionFails() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT))
                    .thenThrow(new IllegalStateException("No operator found"));

            assertThat(service.introMessage(introRequest(CONTACT)).getMessage()).isEqualTo(FALLBACK);
        }
    }

    @Nested
    @DisplayName("closingMessage")
    class Closing {

        @BeforeEach
        void stubClosingContact() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(3, "Asha"));
        }

        @Test
        void rendersTheScreenMessageTemplate() {
            when(templatesService.resolveScreenMessage(3, "CLOSING_MESSAGE", "english"))
                    .thenReturn(Optional.of("Thanks, goodbye."));

            var response = service.closingMessage(closingRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("Thanks, goodbye.");
        }

        @Test
        void doesNotSubstituteNameIntoTheClosingTemplate() {
            when(templatesService.resolveScreenMessage(anyInt(), eq("CLOSING_MESSAGE"), anyString()))
                    .thenReturn(Optional.of("Bye {name}"));

            assertThat(service.closingMessage(closingRequest(CONTACT)).getMessage()).isEqualTo("Bye {name}");
        }

        @Test
        void fallsBackToTheLegacyLanguageScopedConfigKey() {
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "closing_message_english"))
                    .thenReturn(Optional.of("Legacy bye."));

            assertThat(service.closingMessage(closingRequest(CONTACT)).getMessage()).isEqualTo("Legacy bye.");
        }

        @Test
        void fallsBackToTheUnscopedLegacyKeyForEnglish() {
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "closing_message_english")).thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "closing_message"))
                    .thenReturn(Optional.of("Generic bye."));

            assertThat(service.closingMessage(closingRequest(CONTACT)).getMessage()).isEqualTo("Generic bye.");
        }

        @Test
        void doesNotFallBackToTheUnscopedKeyForANonEnglishLanguage() {
            when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("Hindi");
            when(localizationService.normalizeLanguageKey("Hindi")).thenReturn("hindi");
            when(templatesService.resolveScreenMessage(anyInt(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(3, "closing_message_hindi")).thenReturn(Optional.empty());

            assertThat(service.closingMessage(closingRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void returnsTheGenericFallbackForAMissingContactId() {
            assertThat(service.closingMessage(closingRequest(null)).isSuccess()).isFalse();
            assertThat(service.closingMessage(closingRequest("")).getMessage()).isEqualTo(FALLBACK);
        }

        @Test
        void returnsTheGenericFallbackWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null, "Asha"));

            assertThat(service.closingMessage(closingRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void returnsTheGenericFallbackWhenOperatorResolutionFails() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT))
                    .thenThrow(new IllegalStateException("boom"));

            assertThat(service.closingMessage(closingRequest(CONTACT)).getMessage()).isEqualTo(FALLBACK);
        }
    }
}
