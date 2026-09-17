package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
@DisplayName("GlificMeterWorkflowService — prompts")
class GlificMeterWorkflowServicePromptTest {

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
    private TelemetryEventPublisher telemetryEventPublisher;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GlificMeterWorkflowService service;

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
            when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, 11L)).thenReturn(Optional.of(7L));
            when(telemetryTenantRepository.upsertPendingMeterChangeRecord(
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class), anyString()))
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
                    eq(SCHEMA), eq(7L), eq(11L), any(LocalDateTime.class), eq("Meter replaced"));
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
                    anyString(), anyLong(), anyLong(), any(LocalDateTime.class), anyString());
        }

        @Test
        void failsGracefullyWhenTheOperatorIsMappedToNoScheme() {
            when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, 11L)).thenReturn(Optional.empty());

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
                    new GlificMessageTemplatesService.TemplateOption("POWER", 1,
                            java.util.Map.of("en", "No electricity")),
                    new GlificMessageTemplatesService.TemplateOption("PUMP", 2,
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
     * These two return a raw JSON body straight to Glific and, unlike the prompt builders, propagate
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
}
