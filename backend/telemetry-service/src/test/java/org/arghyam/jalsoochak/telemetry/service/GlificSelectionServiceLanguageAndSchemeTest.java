package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeOption;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Language and scheme steps of the Glific selection flow.
 *
 * <p>Both steps accept the operator's reply either as the list number they were shown or as the
 * label itself, in any configured language — so selection resolution is the interesting part, along
 * with the localized error copy each failure returns instead of throwing.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificSelectionService — language and scheme steps")
class GlificSelectionServiceLanguageAndSchemeTest {

    private static final String CONTACT = "919999900001";
    private static final String SCHEMA = "tenant_as";
    private static final int TENANT = 17;

    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private GlificLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificMessageTemplatesService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Mock
    private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock
    private GlificContactSyncService glificContactSyncService;

    private GlificSelectionService service;

    @BeforeEach
    void setUp() {
        service = new GlificSelectionService(
                operatorContextService, localizationService, tenantConfigRepository, templatesService,
                telemetryTenantRepository, userChannelPreferenceRepository, userLanguagePreferenceRepository,
                glificContactSyncService, new ObjectMapper());

        when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(TENANT));
        when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("English");
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("english");
        when(localizationService.resolveLanguageKeyForContact(any())).thenReturn("english");
        when(localizationService.localizeMessage(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(localizationService.resolveUserFacingErrorMessage(any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Exception e = inv.getArgument(0);
                    return e != null && e.getMessage() != null ? e.getMessage() : inv.getArgument(1);
                });
        when(templatesService.resolveScreenOptions(anyInt(), anyString())).thenReturn(List.of());
        when(templatesService.resolveScreenPrompt(anyInt(), anyString(), anyString())).thenReturn(Optional.empty());
        when(templatesService.resolveScreenConfirmationTemplate(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());
    }

    private static TelemetryOperatorWithSchema operator(Integer tenantId) {
        return new TelemetryOperatorWithSchema(SCHEMA,
                new TelemetryOperator(11L, tenantId, "Asha", "a@b.c", CONTACT, 1));
    }

    private static IntroRequest introRequest(String contactId) {
        IntroRequest request = new IntroRequest();
        request.setContactId(contactId);
        return request;
    }

    private static SelectedLanguageRequest languageRequest(String contactId, String language) {
        SelectedLanguageRequest request = new SelectedLanguageRequest();
        request.setContactId(contactId);
        request.setLanguage(language);
        return request;
    }

    private static SelectedSchemeRequest schemeRequest(String contactId, String scheme) {
        SelectedSchemeRequest request = new SelectedSchemeRequest();
        request.setContactId(contactId);
        request.setScheme(scheme);
        return request;
    }

    @Nested
    @DisplayName("languageSelectionMessage")
    class LanguagePrompt {

        @BeforeEach
        void stubLegacyConfig() {
            when(tenantConfigRepository.findLanguageSelectionPrompt(anyInt(), anyString()))
                    .thenReturn(Optional.of("Choose a language:"));
            when(tenantConfigRepository.findLanguageOptions(TENANT))
                    .thenReturn(List.of("English", "Hindi", "Assamese"));
        }

        @Test
        void numbersEveryConfiguredLanguageBeneathThePrompt() {
            var response = service.languageSelectionMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage())
                    .isEqualTo("Choose a language:\n1. English\n2. Hindi\n3. Assamese");
        }

        @Test
        void reportsTheOptionCountAsAWordForTheFlowToBranchOn() {
            assertThat(service.languageSelectionMessage(introRequest(CONTACT)).getCorrelationId())
                    .isEqualTo("three");
        }

        @Test
        void prefersTemplateOptionsOverTheLegacyIndexedKeys() {
            when(templatesService.resolveScreenPrompt(TENANT, "LANGUAGE_SELECTION", "english"))
                    .thenReturn(Optional.of("Pick a language:"));
            when(templatesService.resolveScreenOptions(TENANT, "LANGUAGE_SELECTION")).thenReturn(List.of(
                    new GlificMessageTemplatesService.TemplateOption("EN", 1, Map.of("en", "English")),
                    new GlificMessageTemplatesService.TemplateOption("HI", 2, Map.of("en", "Hindi"))));

            var response = service.languageSelectionMessage(introRequest(CONTACT));

            assertThat(response.getMessage()).isEqualTo("Pick a language:\n1. English\n2. Hindi");
            verify(tenantConfigRepository, never()).findLanguageOptions(anyInt());
        }

        @Test
        void failsGracefullyForAMissingContactId() {
            assertThat(service.languageSelectionMessage(introRequest(null)).isSuccess()).isFalse();
            assertThat(service.languageSelectionMessage(introRequest("  ")).getMessage())
                    .isEqualTo("Language selection could not be prepared.");
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.languageSelectionMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenNoPromptIsConfigured() {
            when(tenantConfigRepository.findLanguageSelectionPrompt(anyInt(), anyString()))
                    .thenReturn(Optional.empty());

            assertThat(service.languageSelectionMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenNoLanguagesAreConfigured() {
            when(tenantConfigRepository.findLanguageOptions(TENANT)).thenReturn(List.of());

            assertThat(service.languageSelectionMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("selectedLanguageMessage")
    class LanguageSelection {

        @BeforeEach
        void stubLanguageOptions() {
            when(tenantConfigRepository.findLanguageOptions(TENANT))
                    .thenReturn(List.of("English", "Hindi", "Assamese"));
        }

        @ParameterizedTest(name = "reply \"{0}\" selects Hindi")
        @ValueSource(strings = {"2", "Hindi", "hindi", "  HINDI  ", "2. Hindi"})
        void acceptsEitherTheListNumberOrTheLabel(String reply) {
            service.selectedLanguageMessage(languageRequest(CONTACT, reply));

            verify(telemetryTenantRepository).updateUserLanguageId(SCHEMA, 11L, 2);
        }

        @Test
        void persistsTheSelectionAndSyncsItToGlific() {
            var response = service.selectedLanguageMessage(languageRequest(CONTACT, "2"));

            assertThat(response.isSuccess()).isTrue();
            verify(telemetryTenantRepository).updateUserLanguageId(SCHEMA, 11L, 2);
            verify(userLanguagePreferenceRepository).upsert(TENANT, CONTACT, "Hindi");
            verify(glificContactSyncService).syncContactLanguageAsync(CONTACT, "Hindi");
        }

        @Test
        void confirmsWithTheDefaultTemplateWhenNoneIsConfigured() {
            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "2")).getMessage())
                    .isEqualTo("Language selected: Hindi");
        }

        @Test
        void prefersTheTenantConfirmationTemplate() {
            when(templatesService.resolveScreenConfirmationTemplate(anyInt(), eq("LANGUAGE_SELECTION"), anyString()))
                    .thenReturn(Optional.of("You picked {language}."));

            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "2")).getMessage())
                    .isEqualTo("You picked Hindi.");
        }

        @Test
        void fallsBackToTheLegacyLanguageScopedConfirmationKey() {
            when(tenantConfigRepository.findConfigValue(TENANT, "language_selection_confirmation_template_english"))
                    .thenReturn(Optional.of("Legacy: {language}"));

            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "2")).getMessage())
                    .isEqualTo("Legacy: Hindi");
        }

        @Test
        void usesTemplateOptionsWhenConfiguredAndPersistsTheCanonicalLabel() {
            when(templatesService.resolveScreenOptions(TENANT, "LANGUAGE_SELECTION")).thenReturn(List.of(
                    new GlificMessageTemplatesService.TemplateOption("EN", 1, Map.of("en", "English")),
                    new GlificMessageTemplatesService.TemplateOption("HI", 2,
                            Map.of("en", "Hindi", "hi", "हिंदी"))));

            service.selectedLanguageMessage(languageRequest(CONTACT, "2"));

            // The canonical (English) label is stored so downstream normalization keeps working.
            verify(userLanguagePreferenceRepository).upsert(TENANT, CONTACT, "Hindi");
            verify(telemetryTenantRepository).updateUserLanguageId(SCHEMA, 11L, 2);
        }

        @Test
        void rejectsAMissingContactIdOrLanguage() {
            assertThat(service.selectedLanguageMessage(languageRequest(null, "2")).isSuccess()).isFalse();
            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "  ")).isSuccess()).isFalse();
            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, null)).getMessage())
                    .isEqualTo("language selection is required");
        }

        @Test
        void rejectsAnOutOfRangeOrUnknownSelection() {
            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "9")).getMessage())
                    .isEqualTo("Invalid language selection");
            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "Klingon")).getMessage())
                    .isEqualTo("Invalid language selection");

            verify(telemetryTenantRepository, never()).updateUserLanguageId(anyString(), anyLong(), anyInt());
        }

        @Test
        void failsGracefullyWhenNoLanguagesAreConfigured() {
            when(tenantConfigRepository.findLanguageOptions(TENANT)).thenReturn(List.of());

            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "2")).getMessage())
                    .isEqualTo("No language options configured for tenant");
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.selectedLanguageMessage(languageRequest(CONTACT, "2")).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenPersistingTheSelectionThrows() {
            org.mockito.Mockito.doThrow(new IllegalStateException("column missing"))
                    .when(telemetryTenantRepository).updateUserLanguageId(anyString(), anyLong(), anyInt());

            var response = service.selectedLanguageMessage(languageRequest(CONTACT, "2"));

            assertThat(response.isSuccess()).isFalse();
            verify(glificContactSyncService, never()).syncContactLanguageAsync(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("schemeSelectionMessage")
    class SchemePrompt {

        @Test
        void numbersEverySchemeMappedToTheOperator() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L)).thenReturn(List.of(
                    new TelemetrySchemeOption(5L, "Scheme A"),
                    new TelemetrySchemeOption(6L, "Scheme B")));

            var response = service.schemeSelectionMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("Please select a scheme:\n1. Scheme A\n2. Scheme B");
            assertThat(response.getIsSchemeGreaterThanOne()).isTrue();
        }

        @Test
        void flagsASingleSchemeSoTheFlowCanSkipTheStep() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L))
                    .thenReturn(List.of(new TelemetrySchemeOption(5L, "Scheme A")));

            assertThat(service.schemeSelectionMessage(introRequest(CONTACT)).getIsSchemeGreaterThanOne())
                    .isFalse();
        }

        @Test
        void labelsAnUnnamedSchemeByItsId() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L)).thenReturn(List.of(
                    new TelemetrySchemeOption(5L, null),
                    new TelemetrySchemeOption(6L, "  ")));

            assertThat(service.schemeSelectionMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Please select a scheme:\n1. Scheme 5\n2. Scheme 6");
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoSchemes() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L)).thenReturn(List.of());

            var response = service.schemeSelectionMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo("Operator is not mapped to any scheme");
        }

        @Test
        void failsGracefullyForAMissingContactId() {
            assertThat(service.schemeSelectionMessage(introRequest(null)).isSuccess()).isFalse();
            assertThat(service.schemeSelectionMessage(introRequest("")).isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("selectedSchemeMessage")
    class SchemeSelection {

        @BeforeEach
        void stubSchemes() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L)).thenReturn(List.of(
                    new TelemetrySchemeOption(5L, "Scheme A"),
                    new TelemetrySchemeOption(6L, "Scheme B")));
            when(telemetryTenantRepository.upsertPendingSchemeSelectionRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class)))
                    .thenReturn("scheme-selection-abc");
        }

        @ParameterizedTest(name = "reply \"{0}\" selects scheme {1}")
        @CsvSource({
                "1,5",          // list position
                "2,6",
                "Scheme A,5",   // exact label
                "scheme b,6",   // label, case-insensitive
                "5,5"           // the scheme id itself
        })
        void acceptsTheListNumberTheLabelOrTheSchemeId(String reply, long expectedSchemeId) {
            var response = service.selectedSchemeMessage(schemeRequest(CONTACT, reply));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getSelected()).isEqualTo(String.valueOf(expectedSchemeId));
        }

        @Test
        void recordsThePendingSelectionAndReturnsItsCorrelationId() {
            var response = service.selectedSchemeMessage(schemeRequest(CONTACT, "1"));

            assertThat(response.getCorrelationId()).isEqualTo("scheme-selection-abc");
            assertThat(response.getMessage()).isEqualTo("Scheme selected: Scheme A");
            verify(telemetryTenantRepository).upsertPendingSchemeSelectionRecord(
                    eq(SCHEMA), eq(5L), eq(11L), any(LocalDateTime.class));
        }

        @Test
        void labelsAnUnnamedSelectedSchemeByItsId() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L))
                    .thenReturn(List.of(new TelemetrySchemeOption(5L, null)));

            assertThat(service.selectedSchemeMessage(schemeRequest(CONTACT, "1")).getMessage())
                    .isEqualTo("Scheme selected: Scheme 5");
        }

        @Test
        void rejectsAMissingContactIdOrScheme() {
            assertThat(service.selectedSchemeMessage(schemeRequest(null, "1")).isSuccess()).isFalse();
            assertThat(service.selectedSchemeMessage(schemeRequest(CONTACT, "  ")).getMessage())
                    .isEqualTo("scheme selection is required");
        }

        @Test
        void rejectsAnUnknownSelection() {
            assertThat(service.selectedSchemeMessage(schemeRequest(CONTACT, "Scheme Z")).getMessage())
                    .isEqualTo("Invalid scheme selection");
            assertThat(service.selectedSchemeMessage(schemeRequest(CONTACT, "99")).getMessage())
                    .isEqualTo("Invalid scheme selection");

            verify(telemetryTenantRepository, never()).upsertPendingSchemeSelectionRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class));
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoSchemes() {
            when(telemetryTenantRepository.findSchemesForUser(SCHEMA, 11L)).thenReturn(List.of());

            assertThat(service.selectedSchemeMessage(schemeRequest(CONTACT, "1")).getMessage())
                    .isEqualTo("Operator is not mapped to any scheme");
        }

        @Test
        void failsGracefullyWhenRecordingTheSelectionThrows() {
            when(telemetryTenantRepository.upsertPendingSchemeSelectionRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class)))
                    .thenThrow(new IllegalStateException("insert failed"));

            assertThat(service.selectedSchemeMessage(schemeRequest(CONTACT, "1")).isSuccess()).isFalse();
        }
    }
}
