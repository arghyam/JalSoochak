package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

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
 * The numbered-menu prompts of the meter workflow — meter change, take-reading, and the two
 * issue-report entry points. Each renders the tenant's configured reasons as a numbered list and
 * degrades to a fixed message rather than throwing, so the operator always gets a reply.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MeterReadingConversationService — prompts")
class MeterReadingConversationServicePromptTest {

    private static final String CONTACT = "919999900001";
    private static final String SCHEMA = "tenant_as";
    private static final int TENANT = 17;

    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ConversationLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private ConversationTemplateService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private ConversationSchemeResolver conversationSchemeResolver;

    @Spy
    private ConversationMessageResolver messageResolver = ConversationMessageResolverFixtures.real();

    @InjectMocks
    private MeterReadingConversationService service;

    private static TelemetryOperatorWithSchema operator(Integer tenantId) {
        return new TelemetryOperatorWithSchema(SCHEMA,
                new TelemetryOperator(11L, tenantId, "Asha", "a@b.c", CONTACT, 1));
    }

    private static IntroRequest introRequest(String contactId) {
        IntroRequest request = new IntroRequest();
        request.setContactId(contactId);
        return request;
    }

    @BeforeEach
    void setUp() {
        when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(TENANT));
        when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("English");
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("english");
        when(localizationService.resolveLanguageKeyForContact(any())).thenReturn("english");
        when(localizationService.localizeMessage(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(templatesService.toTemplateLanguageCode("english")).thenReturn("en");
        when(templatesService.toTemplateLanguageCode("hindi")).thenReturn("hi");
        when(templatesService.resolveScreenOptions(anyInt(), anyString())).thenReturn(List.of());
        when(templatesService.resolveScreenReasons(anyInt(), anyString())).thenReturn(List.of());
        when(templatesService.resolveScreenPrompt(anyInt(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("meterChangeMessage")
    class MeterChangePrompt {

        @BeforeEach
        void stubConfig() {
            when(tenantConfigRepository.findMeterChangePrompt(TENANT, "english"))
                    .thenReturn(Optional.of("Why is the meter being changed?"));
            when(tenantConfigRepository.findMeterChangeReasons(TENANT, "english"))
                    .thenReturn(List.of("Meter replaced", "Meter not working"));
        }

        @Test
        void numbersEveryConfiguredReasonBeneathThePrompt() {
            var response = service.meterChangeMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage())
                    .isEqualTo("Why is the meter being changed?\n1. Meter replaced\n2. Meter not working");
        }

        @Test
        void acceptsAMeterChangeRequestAsWellAsAnIntroRequest() {
            var response = service.meterChangeMessage(
                    MeterChangeRequest.builder().contactId(CONTACT).build());

            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        void failsGracefullyForAMissingContactId() {
            assertThat(service.meterChangeMessage(introRequest(null)).isSuccess()).isFalse();
            assertThat(service.meterChangeMessage(introRequest("  ")).getMessage())
                    .isEqualTo("Meter change reasons could not be prepared.");
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.meterChangeMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenNoPromptIsConfigured() {
            when(tenantConfigRepository.findMeterChangePrompt(anyInt(), anyString())).thenReturn(Optional.empty());

            assertThat(service.meterChangeMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenNoReasonsAreConfigured() {
            when(tenantConfigRepository.findMeterChangeReasons(anyInt(), anyString())).thenReturn(List.of());

            assertThat(service.meterChangeMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenOperatorResolutionFails() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT))
                    .thenThrow(new IllegalStateException("No operator found"));

            assertThat(service.meterChangeMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("takeMeterReadingMessage")
    class TakeMeterReadingPrompt {

        @BeforeEach
        void stubConfig() {
            when(tenantConfigRepository.findMeterChangeReasons(TENANT, "english"))
                    .thenReturn(List.of("Meter replaced", "Meter not working"));
            when(conversationSchemeResolver.require(operator(TENANT))).thenReturn(7L);
            when(telemetryTenantRepository.upsertPendingMeterChangeRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class), anyString(), any()))
                    .thenReturn("meter-change-abc");
        }

        private MeterChangeRequest request(String contactId, String reason) {
            return MeterChangeRequest.builder().contactId(contactId).reason(reason).build();
        }

        @ParameterizedTest(name = "reply \"{0}\" selects \"Meter replaced\"")
        @ValueSource(strings = {"1", "Meter replaced", "meter replaced", "  METER REPLACED  "})
        void acceptsEitherTheListNumberOrTheReasonLabel(String reply) {
            service.takeMeterReadingMessage(request(CONTACT, reply));

            verify(telemetryTenantRepository).upsertPendingMeterChangeRecord(
                    eq(SCHEMA), eq(7L), eq(11L), any(LocalDateTime.class), eq("Meter replaced"), eq(ReportingChannel.WHATSAPP));
        }

        @Test
        void recordsThePendingChangeAndReturnsItsCorrelationId() {
            var response = service.takeMeterReadingMessage(request(CONTACT, "1"));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getCorrelationId()).isEqualTo("meter-change-abc");
        }

        @Test
        void usesTheDefaultPromptWhenTheTenantConfiguresNone() {
            when(tenantConfigRepository.findTakeMeterReadingPrompt(anyInt(), anyString()))
                    .thenReturn(Optional.empty());

            assertThat(service.takeMeterReadingMessage(request(CONTACT, "1")).getMessage())
                    .isEqualTo("Please type your meter reading manually (numbers only).");
        }

        @Test
        void asksForTheDefaultPromptInTheOperatorsLanguage() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");
            when(tenantConfigRepository.findMeterChangeReasons(TENANT, "hindi")).thenReturn(List.of("Meter replaced"));

            service.takeMeterReadingMessage(request(CONTACT, "1"));

            verify(messageResolver).get(ConversationMessageCode.TAKE_READING_PROMPT, "hindi");
        }

        @Test
        void prefersTheTenantConfiguredPrompt() {
            when(tenantConfigRepository.findTakeMeterReadingPrompt(TENANT, "english"))
                    .thenReturn(Optional.of("Send the new reading."));

            assertThat(service.takeMeterReadingMessage(request(CONTACT, "1")).getMessage())
                    .isEqualTo("Send the new reading.");
        }

        @Test
        void rejectsAMissingContactIdOrReason() {
            assertThat(service.takeMeterReadingMessage(request(null, "1")).isSuccess()).isFalse();
            assertThat(service.takeMeterReadingMessage(request(CONTACT, "  ")).isSuccess()).isFalse();
        }

        @Test
        void rejectsAnUnknownReasonSelection() {
            var response = service.takeMeterReadingMessage(request(CONTACT, "9"));

            assertThat(response.isSuccess()).isFalse();
            verify(telemetryTenantRepository, never()).upsertPendingMeterChangeRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class), anyString(), any());
        }

        @Test
        void failsGracefullyWhenTheOperatorIsMappedToNoScheme() {
            when(conversationSchemeResolver.require(operator(TENANT)))
                    .thenThrow(new IllegalStateException(ConversationSchemeResolver.NOT_MAPPED_MESSAGE));

            assertThat(service.takeMeterReadingMessage(request(CONTACT, "1")).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenNoReasonsAreConfigured() {
            when(tenantConfigRepository.findMeterChangeReasons(anyInt(), anyString())).thenReturn(List.of());

            assertThat(service.takeMeterReadingMessage(request(CONTACT, "1")).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.takeMeterReadingMessage(request(CONTACT, "1")).isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("issue-report prompts")
    class IssueReportPrompts {

        @BeforeEach
        void stubConfig() {
            when(tenantConfigRepository.findIssueReportPrompt(TENANT, "english"))
                    .thenReturn(Optional.of("What is the issue?"));
            when(tenantConfigRepository.findIssueReportReasons(TENANT, "english"))
                    .thenReturn(List.of("No electricity", "Pump failure"));
        }

        @Test
        void issueReportPromptNumbersEveryConfiguredReason() {
            var response = service.issueReportPromptMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).contains("1. No electricity").contains("2. Pump failure");
        }

        @Test
        void issueReportPromptFailsGracefullyForAMissingContactId() {
            assertThat(service.issueReportPromptMessage(introRequest(null)).isSuccess()).isFalse();
        }

        @Test
        void issueReportPromptFallsBackToBuiltInReasonsWhenTheTenantConfiguresNone() {
            when(tenantConfigRepository.findIssueReportPrompt(anyInt(), anyString())).thenReturn(Optional.empty());
            when(tenantConfigRepository.findIssueReportReasons(anyInt(), anyString())).thenReturn(List.of());

            var response = service.issueReportPromptMessage(introRequest(CONTACT));

            // The step must still offer a menu; an unconfigured tenant gets the built-in reason list.
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).contains("1. ");
        }

        @Test
        void issueReportPromptPrefersTemplateReasonsOverTheLegacyKeys() {
            when(templatesService.resolveScreenReasons(TENANT, "ISSUE_REPORT")).thenReturn(List.of(
                    new ConversationTemplateService.TemplateOption("POWER", 1,
                            java.util.Map.of("en", "No electricity")),
                    new ConversationTemplateService.TemplateOption("PUMP", 2,
                            java.util.Map.of("en", "Pump failure"))));

            assertThat(service.issueReportPromptMessage(introRequest(CONTACT)).getMessage())
                    .contains("1. No electricity")
                    .contains("2. Pump failure");
        }

        @Test
        void issueReportPromptFailsGracefullyWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.issueReportPromptMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void telemetryIssueReportPromptFailsGracefullyForAMissingContactId() {
            assertThat(service.issueReportTelemetryPromptMessage(introRequest(null)).isSuccess()).isFalse();
        }

        @Test
        void othersPromptFailsGracefullyForAMissingContactId() {
            assertThat(service.othersPromptMessage(introRequest(null)).isSuccess()).isFalse();
        }

        @Test
        void othersPromptAsksTheOperatorToDescribeTheIssue() {
            var response = service.othersPromptMessage(introRequest(CONTACT));

            assertThat(response).isNotNull();
        }
    }

    /**
     * The nudge flow's "Select reason" menu. The flow routes on the reply number itself, so option 1
     * must always be no reading submission and option 2 no water supply, whatever the tenant's own
     * issue-report order.
     */
    @Nested
    @DisplayName("issueReasonTypesMessage")
    class IssueReasonTypes {

        private final List<ConversationTemplateService.TemplateOption> templateReasons = List.of(
                new ConversationTemplateService.TemplateOption("REASON_4", 1,
                        java.util.Map.of("en", "Water not supplied", "hi", "पानी नहीं आया")),
                new ConversationTemplateService.TemplateOption("REASON_1", 2,
                        java.util.Map.of("en", "Meter Replaced")),
                new ConversationTemplateService.TemplateOption("REASON_3", 3,
                        java.util.Map.of("en", "Reading not sent", "hi", "रीडिंग नहीं भेजी")));

        @BeforeEach
        void stubConfig() {
            when(tenantConfigRepository.findIssueReportPrompt(TENANT, "english"))
                    .thenReturn(Optional.of("What is the issue?"));
        }

        @Test
        void listsNoReadingSubmissionFirstAndNoWaterSupplySecondWhateverTheConfiguredOrder() {
            when(templatesService.resolveScreenReasons(TENANT, "ISSUE_REPORT")).thenReturn(templateReasons);

            var response = service.issueReasonTypesMessage(introRequest(CONTACT));

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage())
                    .isEqualTo("What is the issue?\n1. Reading not sent\n2. Water not supplied");
        }

        @Test
        void usesTheOperatorsLanguageForTheLabels() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");
            when(tenantConfigRepository.findIssueReportPrompt(TENANT, "hindi")).thenReturn(Optional.of("समस्या चुनें"));
            when(templatesService.resolveScreenReasons(TENANT, "ISSUE_REPORT")).thenReturn(templateReasons);

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("समस्या चुनें\n1. रीडिंग नहीं भेजी\n2. पानी नहीं आया");
        }

        @Test
        void acceptsTheLegacyNoWaterSuppliedKey() {
            when(templatesService.resolveScreenReasons(TENANT, "ISSUE_REPORT")).thenReturn(List.of(
                    new ConversationTemplateService.TemplateOption("noWaterSupplied", 1,
                            java.util.Map.of("en", "Supply stopped"))));

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).getMessage())
                    .endsWith("\n2. Supply stopped");
        }

        @Test
        void fallsBackToBuiltInLabelsForAReasonTheTenantDoesNotConfigure() {
            when(templatesService.resolveScreenReasons(TENANT, "ISSUE_REPORT")).thenReturn(List.of(
                    new ConversationTemplateService.TemplateOption("REASON_4", 1,
                            java.util.Map.of("en", "Water not supplied"))));

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("What is the issue?\n1. No Reading Submission\n2. Water not supplied");
        }

        @Test
        void fallsBackToBuiltInLabelsAndPromptWhenTheTenantConfiguresNothing() {
            when(tenantConfigRepository.findIssueReportPrompt(anyInt(), anyString())).thenReturn(Optional.empty());

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Please select your issue by typing any of the number"
                            + "\n1. No Reading Submission\n2. No Water Supply");
        }

        @Test
        void fallsBackToBuiltInHindiLabelsForAHindiOperator() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");
            when(tenantConfigRepository.findIssueReportPrompt(anyInt(), anyString())).thenReturn(Optional.empty());

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("कृपया नंबर टाइप करके अपनी समस्या चुनें"
                            + "\n1. रीडिंग जमा नहीं हुई\n2. पानी की आपूर्ति नहीं");
        }

        @Test
        void prefersTheTemplatePromptLikeTheIssueReportMenu() {
            when(templatesService.resolveScreenPrompt(TENANT, "ISSUE_REPORT", "english"))
                    .thenReturn(Optional.of("Why no reading today?"));

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).getMessage())
                    .startsWith("Why no reading today?\n1. ");
        }

        @Test
        void failsGracefullyForAMissingContactId() {
            assertThat(service.issueReasonTypesMessage(introRequest(null)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }

        @Test
        void failsGracefullyWhenOperatorResolutionFails() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT))
                    .thenThrow(new IllegalStateException("unknown contact"));

            assertThat(service.issueReasonTypesMessage(introRequest(CONTACT)).isSuccess()).isFalse();
        }
    }

    /**
     * These two return a raw JSON body straight to the chatbot and, unlike the prompt builders, propagate
     * their failures — the controller turns them into a JSON error body.
     */
    @Nested
    @DisplayName("raw JSON reason listings")
    class ReasonListings {

        private static final String METER_CHANGE_JSON = """
                {"reasons":[
                  {"name":"Meter not working","sequenceOrder":2},
                  {"name":"Meter replaced","sequenceOrder":1},
                  {"name":"  ","sequenceOrder":3}
                ]}
                """;

        private static final String OUTAGE_JSON = """
                {"reasons":[
                  {"name":"Pump failure","sequenceOrder":2},
                  {"name":"No electricity","sequenceOrder":1}
                ]}
                """;

        @Test
        void meterChangeReasonsRendersTheConfiguredReasonsInSequenceOrder() {
            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.of(METER_CHANGE_JSON));

            String json = service.meterChangeReasons(introRequest(CONTACT));

            assertThat(json).contains("\"success\":true");
            assertThat(json).contains("1. Meter replaced");
            assertThat(json).contains("2. Meter not working");
        }

        @Test
        void meterChangeReasonsSkipsAnUnnamedReason() {
            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.of(METER_CHANGE_JSON));

            assertThat(service.meterChangeReasons(introRequest(CONTACT))).doesNotContain("3. ");
        }

        @Test
        void meterChangeReasonsFailsWhenTheConfigIsMissingInvalidOrEmpty() {
            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.empty());
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.meterChangeReasons(introRequest(CONTACT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not configured");

            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.of("{ not json"));
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.meterChangeReasons(introRequest(CONTACT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not valid JSON");

            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.of("{\"reasons\":[]}"));
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.meterChangeReasons(introRequest(CONTACT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no reasons");
        }

        @Test
        void telemetryIssueReportReasonsRendersTheConfiguredOutageReasons() {
            when(tenantConfigRepository.findConfigValue(TENANT, "SUPPLY_OUTAGE_REASONS"))
                    .thenReturn(Optional.of(OUTAGE_JSON));

            String json = service.issueReportTelemetryReasons(introRequest(CONTACT));

            assertThat(json).contains("\"success\":true");
            assertThat(json).contains("1. No electricity");
            assertThat(json).contains("2. Pump failure");
        }

        @Test
        void telemetryIssueReportReasonsFailsWhenTheConfigIsMissing() {
            when(tenantConfigRepository.findConfigValue(TENANT, "SUPPLY_OUTAGE_REASONS"))
                    .thenReturn(Optional.empty());

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.issueReportTelemetryReasons(introRequest(CONTACT)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPPLY_OUTAGE_REASONS");
        }

        @Test
        void reasonListingsRejectAMissingContactId() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.meterChangeReasons(introRequest(null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("contactId is required");

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.issueReportTelemetryReasons(introRequest("  ")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("contactId is required");
        }

        @Test
        void reasonListingsRejectAnOperatorWithNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.meterChangeReasons(introRequest(CONTACT)))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.issueReportTelemetryReasons(introRequest(CONTACT)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * The labels of a tenant's configured reasons: a default reason is recognised by its id, and
     * translated only while it keeps the name tenant-service seeded it with.
     */
    @Nested
    @DisplayName("configured reason labels")
    class ConfiguredReasonLabels {

        /** tenant-service's seeded {@code meter-change-reasons}. */
        private static final String SEEDED_METER_CHANGE_JSON = """
                {"reasons":[
                  {"id":"METER_REPLACED","name":"Meter Replaced","sequenceOrder":1},
                  {"id":"METER_NOT_WORKING","name":"Meter Not Working","sequenceOrder":2},
                  {"id":"METER_DAMAGED","name":"Meter Damaged","sequenceOrder":3}
                ]}
                """;

        /** tenant-service's seeded {@code supply-outage-reasons}. */
        private static final String SEEDED_OUTAGE_JSON = """
                {"reasons":[
                  {"id":"ELECTRICITY_SUPPLY_DISCONNECTED","name":"Electricity Supply Disconnected","sequenceOrder":1},
                  {"id":"NO_ELECTRICITY_SUPPLY_TODAY","name":"No Electricity Supply Today","sequenceOrder":2},
                  {"id":"PUMP_FAILURE","name":"Pump Failure","sequenceOrder":3},
                  {"id":"PIPELINE_BREAK","name":"Pipeline Break","sequenceOrder":4},
                  {"id":"WATER_QUALITY_ISSUES","name":"Water Quality Issues","sequenceOrder":5},
                  {"id":"SOURCE_DRYING","name":"Source Drying","sequenceOrder":6},
                  {"id":"NATURAL_CALAMITY","name":"Natural Calamity","sequenceOrder":7},
                  {"id":"OTHERS","name":"Others","sequenceOrder":8}
                ]}
                """;

        private void operatorReads(String languageKey) {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn(languageKey);
        }

        private void outageReasonsAre(String json) {
            when(tenantConfigRepository.findConfigValue(TENANT, "SUPPLY_OUTAGE_REASONS"))
                    .thenReturn(Optional.of(json));
        }

        private String outageListing() throws Exception {
            return objectMapper.readTree(service.issueReportTelemetryReasons(introRequest(CONTACT)))
                    .path("message").asText();
        }

        @Test
        void translatesTheSeededMeterChangeReasons() throws Exception {
            operatorReads("hindi");
            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.of(SEEDED_METER_CHANGE_JSON));

            assertThat(objectMapper.readTree(service.meterChangeReasons(introRequest(CONTACT)))
                    .path("message").asText())
                    .isEqualTo("कृपया नंबर टाइप करके सबमिशन न होने के कारण चुनें"
                            + "\n1. मीटर बदला गया\n2. मीटर काम नहीं कर रहा\n3. मीटर खराब है");
        }

        @Test
        void translatesTheSeededSupplyOutageReasons() throws Exception {
            operatorReads("hindi");
            outageReasonsAre(SEEDED_OUTAGE_JSON);

            assertThat(outageListing())
                    .isEqualTo("कृपया अपनी समस्या चुनें।"
                            + "\n1. बिजली आपूर्ति बंद है\n2. आज बिजली की आपूर्ति नहीं है\n3. पंप खराब है"
                            + "\n4. पाइपलाइन टूटी है\n5. पानी की गुणवत्ता में समस्या है\n6. स्रोत सूख रहा है"
                            + "\n7. प्राकृतिक आपदा\n8. अन्य");
        }

        @Test
        void showsTheSeededNamesAsConfiguredToAnEnglishOperator() throws Exception {
            outageReasonsAre(SEEDED_OUTAGE_JSON);

            assertThat(outageListing()).contains("\n2. No Electricity Supply Today\n3. Pump Failure\n");
        }

        @Test
        void showsTheSeededNamesAsConfiguredInALanguageThatDoesNotTranslateThem() throws Exception {
            operatorReads("assamese");
            outageReasonsAre(SEEDED_OUTAGE_JSON);

            assertThat(outageListing()).contains("\n2. No Electricity Supply Today\n3. Pump Failure\n");
        }

        @Test
        void showsARenamedDefaultReasonAsTheTenantWroteIt() throws Exception {
            operatorReads("hindi");
            outageReasonsAre("""
                    {"reasons":[{"id":"PUMP_FAILURE","name":"Motor burnt","sequenceOrder":1}]}
                    """);

            assertThat(outageListing()).endsWith("\n1. Motor burnt");
        }

        @Test
        void showsACustomReasonAsTheTenantWroteItEvenWhenItContainsADefaultName() throws Exception {
            operatorReads("hindi");
            outageReasonsAre("""
                    {"reasons":[{"id":"LOW_VOLTAGE","name":"Pump failure due to low voltage","sequenceOrder":1}]}
                    """);

            assertThat(outageListing()).endsWith("\n1. Pump failure due to low voltage");
        }
    }

    /**
     * The built-in English and Hindi copy a tenant gets when it configures none. The localizer is
     * stubbed to pass text through, so these pin which built-in text each language starts from.
     */
    @Nested
    @DisplayName("built-in copy")
    class BuiltInCopy {

        private static final String REASONS_JSON = """
                {"reasons":[{"name":"Meter replaced","sequenceOrder":1}]}
                """;

        @BeforeEach
        void stubNothingConfigured() {
            when(tenantConfigRepository.findIssueReportPrompt(anyInt(), anyString())).thenReturn(Optional.empty());
            when(tenantConfigRepository.findIssueReportReasons(anyInt(), anyString())).thenReturn(List.of());
            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS"))
                    .thenReturn(Optional.of(REASONS_JSON));
            when(tenantConfigRepository.findConfigValue(TENANT, "SUPPLY_OUTAGE_REASONS"))
                    .thenReturn(Optional.of(REASONS_JSON));
        }

        private void operatorReadsHindi() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");
        }

        private String messageOf(String json) throws Exception {
            return objectMapper.readTree(json).path("message").asText();
        }

        @Test
        void issueReportPromptListsTheBuiltInReasonsInEnglish() {
            assertThat(service.issueReportPromptMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Please select your issue by typing any of the number"
                            + "\n1. Meter Replaced\n2. Meter not working\n3. Meter damage"
                            + "\n4. Incorrect Reading Entered Previously\n5. No Water Supply\n6. Others");
        }

        @Test
        void issueReportPromptListsTheBuiltInReasonsInHindi() {
            operatorReadsHindi();

            assertThat(service.issueReportPromptMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("कृपया नंबर टाइप करके अपनी समस्या चुनें"
                            + "\n1. मीटर बदला गया\n2. मीटर काम नहीं कर रहा\n3. मीटर खराब है"
                            + "\n4. पहले गलत रीडिंग दर्ज हुई थी\n5. पानी की आपूर्ति नहीं\n6. अन्य");
        }

        @Test
        void issueReportPromptReplacesTheLegacyEnglishPromptWithTheBuiltInOne() {
            when(tenantConfigRepository.findIssueReportPrompt(anyInt(), anyString()))
                    .thenReturn(Optional.of("Please type your issue in a few words."));

            assertThat(service.issueReportPromptMessage(introRequest(CONTACT)).getMessage())
                    .startsWith("Please select your issue by typing any of the number\n1. ");
        }

        @Test
        void issueReportPromptReplacesTheLegacyHindiPromptWithTheBuiltInOne() {
            operatorReadsHindi();
            when(tenantConfigRepository.findIssueReportPrompt(anyInt(), anyString()))
                    .thenReturn(Optional.of("कृपया अपनी समस्या संक्षेप में लिखें।"));

            assertThat(service.issueReportPromptMessage(introRequest(CONTACT)).getMessage())
                    .startsWith("कृपया नंबर टाइप करके अपनी समस्या चुनें\n1. ");
        }

        @Test
        void telemetryIssueReportPromptListsTheBuiltInReasonsInEnglish() {
            assertThat(service.issueReportTelemetryPromptMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Please select your issue by typing any of the number"
                            + "\n1. Meter Replaced\n2. Meter not working\n3. Meter Damaged"
                            + "\n4. No Water Supply\n5. Others");
        }

        @Test
        void telemetryIssueReportPromptListsTheBuiltInReasonsInHindi() {
            operatorReadsHindi();

            assertThat(service.issueReportTelemetryPromptMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("कृपया नंबर टाइप करके अपनी समस्या चुनें"
                            + "\n1. मीटर बदला गया\n2. मीटर काम नहीं कर रहा\n3. मीटर खराब है"
                            + "\n4. पानी की आपूर्ति नहीं\n5. अन्य");
        }

        @Test
        void othersPromptInEnglish() {
            assertThat(service.othersPromptMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("Please report your issue.");
        }

        @Test
        void othersPromptInHindi() {
            operatorReadsHindi();

            assertThat(service.othersPromptMessage(introRequest(CONTACT)).getMessage())
                    .isEqualTo("कृपया अपनी समस्या बताएं।");
        }

        @Test
        void meterChangeReasonsStartFromTheBuiltInEnglishPrompt() throws Exception {
            assertThat(messageOf(service.meterChangeReasons(introRequest(CONTACT))))
                    .isEqualTo("Please select the no submission reasons by typing any of the number\n1. Meter replaced");
        }

        @Test
        void meterChangeReasonsStartFromTheBuiltInHindiPrompt() throws Exception {
            operatorReadsHindi();

            assertThat(messageOf(service.meterChangeReasons(introRequest(CONTACT))))
                    .isEqualTo("कृपया नंबर टाइप करके सबमिशन न होने के कारण चुनें\n1. Meter replaced");
        }

        @Test
        void supplyOutageReasonsStartFromTheBuiltInEnglishPrompt() throws Exception {
            assertThat(messageOf(service.issueReportTelemetryReasons(introRequest(CONTACT))))
                    .isEqualTo("Please select your issue.\n1. Meter replaced");
        }

        @Test
        void supplyOutageReasonsStartFromTheBuiltInHindiPrompt() throws Exception {
            operatorReadsHindi();

            assertThat(messageOf(service.issueReportTelemetryReasons(introRequest(CONTACT))))
                    .isEqualTo("कृपया अपनी समस्या चुनें।\n1. Meter replaced");
        }
    }

    @Nested
    @DisplayName("meterChangeSubmitMessage")
    class MeterChangeSubmission {

        @BeforeEach
        void stubConfig() {
            when(tenantConfigRepository.findConfigValue(TENANT, "METER_CHANGE_REASONS")).thenReturn(Optional.of(
                    "{\"reasons\":[{\"id\":\"meterDamaged\",\"name\":\"Meter damaged\",\"sequenceOrder\":1}]}"));
        }

        private MeterChangeRequest request(String reason) {
            return MeterChangeRequest.builder().contactId(CONTACT).reason(reason).build();
        }

        @Test
        void rejectsAnOutOfRangeChoice() {
            var response = service.meterChangeSubmitMessage(request("2"));

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getMessage()).isEqualTo("invalid choice, please restart the flow");
            verify(telemetryTenantRepository, never()).upsertPendingMeterChangeRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class), anyString(), any());
        }

        @Test
        void rejectsAnOutOfRangeChoiceInTheOperatorsLanguage() {
            when(localizationService.normalizeLanguageKey(anyString())).thenReturn("hindi");

            service.meterChangeSubmitMessage(request("2"));

            verify(messageResolver).get(ConversationMessageCode.INVALID_CHOICE, "hindi");
        }
    }

    /** Each step's fixed reply when it cannot be built, in the language of the contact who asked. */
    @Nested
    @DisplayName("failure replies")
    class FailureReplies {

        @BeforeEach
        void operatorWithNoTenantWhoReadsHindi() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));
            when(localizationService.resolveLanguageKeyForContact(CONTACT)).thenReturn("hindi");
        }

        static Stream<Arguments> steps() {
            MeterChangeRequest meterChange = MeterChangeRequest.builder().contactId(CONTACT).reason("1").build();
            return Stream.of(
                    step("meterChangeMessage", s -> s.meterChangeMessage(introRequest(CONTACT)),
                            ConversationMessageCode.METER_CHANGE_REASONS_NOT_PREPARED,
                            "Meter change reasons could not be prepared."),
                    step("takeMeterReadingMessage", s -> s.takeMeterReadingMessage(meterChange),
                            ConversationMessageCode.TAKE_READING_PROMPT_NOT_PREPARED,
                            "Take meter reading prompt could not be prepared."),
                    step("meterChangeSubmitMessage", s -> s.meterChangeSubmitMessage(meterChange),
                            ConversationMessageCode.METER_CHANGE_REASON_NOT_SAVED,
                            "Meter change reason could not be saved."),
                    step("issueReportPromptMessage", s -> s.issueReportPromptMessage(introRequest(CONTACT)),
                            ConversationMessageCode.ISSUE_PROMPT_NOT_PREPARED,
                            "Issue report prompt could not be prepared."),
                    step("issueReportTelemetryPromptMessage",
                            s -> s.issueReportTelemetryPromptMessage(introRequest(CONTACT)),
                            ConversationMessageCode.ISSUE_PROMPT_NOT_PREPARED,
                            "Issue report prompt could not be prepared."),
                    step("issueReasonTypesMessage", s -> s.issueReasonTypesMessage(introRequest(CONTACT)),
                            ConversationMessageCode.ISSUE_REASON_TYPES_NOT_PREPARED,
                            "Issue reason types could not be prepared."),
                    step("othersPromptMessage", s -> s.othersPromptMessage(introRequest(CONTACT)),
                            ConversationMessageCode.OTHERS_PROMPT_NOT_PREPARED,
                            "Others prompt could not be prepared."));
        }

        private static Arguments step(String name, Function<MeterReadingConversationService, IntroResponse> call,
                                      ConversationMessageCode code, String english) {
            return Arguments.of(name, call, code, english);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("steps")
        void repliesWithTheStepsFailureCopyInTheOperatorsLanguage(
                String name, Function<MeterReadingConversationService, IntroResponse> call,
                ConversationMessageCode code, String english) {
            IntroResponse response = call.apply(service);

            assertThat(response.isSuccess()).isFalse();
            // No Hindi translation yet, so the copy is the English fallback.
            assertThat(response.getMessage()).isEqualTo(english);
            verify(messageResolver).get(code, "hindi");
        }
    }
}
