package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NUDGE-SCHEME: the issue-report and update-previous-reading paths record against the scheme the
 * operator picked in this conversation, not their first mapped scheme.
 *
 * <p>The v6 flow asks a multi-scheme operator which scheme before each of these paths, and
 * {@code /scheme/selected} stores the answer as today's scheme-selection row. Every test here has
 * a selection of {@link #SELECTED} while the first mapped scheme is {@link #FIRST}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeterReadingConversationServiceSelectedSchemeTest {

    private static final String SCHEMA = "tenant_test";
    private static final String CONTACT = "919999999999";
    private static final long OPERATOR = 1L;
    private static final long FIRST = 10L;
    private static final long SELECTED = 20L;

    @Mock private OperatorContextService operatorContextService;
    @Mock private ConversationLocalizationService localizationService;
    @Mock private TenantConfigRepository tenantConfigRepository;
    @Mock private ConversationTemplateService templatesService;
    @Mock private TelemetryTenantRepository telemetryTenantRepository;
    @Mock private TelemetryEventPublisher telemetryEventPublisher;
    @Spy private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private MeterReadingConversationService service;

    @BeforeEach
    void operatorWithTwoSchemesWhoPickedTheSecond() {
        TelemetryOperatorWithSchema operator = new TelemetryOperatorWithSchema(
                SCHEMA, new TelemetryOperator(OPERATOR, 1, "op", "op@example.com", CONTACT, null));
        when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator);
        when(operatorContextService.resolveOperatorLanguage(operator, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findFirstSchemeForUser(SCHEMA, OPERATOR)).thenReturn(Optional.of(FIRST));
        when(telemetryTenantRepository.findLatestPendingSchemeSelectionForDate(eq(SCHEMA), eq(OPERATOR), any()))
                .thenReturn(Optional.of(new TelemetrySchemeSelectionRecord(5L, SELECTED, "scheme-selection-x")));
    }

    @Test
    void issueReportSubmit_recordsAgainstTheSelectedScheme() {
        when(templatesService.resolveScreenReasons(1, "ISSUE_REPORT")).thenReturn(List.of());
        when(tenantConfigRepository.findIssueReportReasons(1, "english")).thenReturn(List.of());

        service.issueReportSubmitMessage(IssueReportRequest.builder().contactId(CONTACT).issueReason("2").build());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(eq(SCHEMA), argThat(a -> a.schemeId() == SELECTED));
    }

    @Test
    void supplyOutageReasonSubmit_recordsAgainstTheSelectedScheme() {
        service.issueReportTelemetrySubmitMessage(IssueReportRequest.builder().contactId(CONTACT).issueReason("1").build());

        verify(telemetryTenantRepository).upsertPendingIssueReportRecord(eq(SCHEMA), eq(SELECTED), eq(OPERATOR), any(), anyString());
    }

    @Test
    void othersSubmitted_recordsAgainstTheSelectedScheme() {
        service.othersSubmittedMessage(IssueReportRequest.builder().contactId(CONTACT).issueReason("Pipe burst").build());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(eq(SCHEMA), argThat(a -> a.schemeId() == SELECTED));
    }

    @Test
    void meterChangeSubmit_recordsAgainstTheSelectedScheme() {
        when(tenantConfigRepository.findConfigValue(1, "METER_CHANGE_REASONS")).thenReturn(Optional.of(
                "{\"reasons\":[{\"id\":\"meterDamaged\",\"name\":\"Meter damaged\",\"sequenceOrder\":1}]}"));

        service.meterChangeSubmitMessage(MeterChangeRequest.builder().contactId(CONTACT).reason("1").build());

        verify(telemetryTenantRepository).upsertPendingMeterChangeRecord(eq(SCHEMA), eq(SELECTED), eq(OPERATOR), any(), eq("Meter damaged"));
    }

    @Test
    void takeMeterReading_recordsAgainstTheSelectedScheme() {
        when(tenantConfigRepository.findMeterChangeReasons(any(), any())).thenReturn(List.of("Meter replaced"));

        service.takeMeterReadingMessage(MeterChangeRequest.builder().contactId(CONTACT).reason("1").build());

        verify(telemetryTenantRepository).upsertPendingMeterChangeRecord(eq(SCHEMA), eq(SELECTED), eq(OPERATOR), any(), eq("Meter replaced"));
    }

    @Test
    void updatePreviousReading_looksUpTheSelectedSchemesReading() {
        service.updatePreviousReadingMessage(UpdatedPreviousReadingRequest.builder().contactId(CONTACT).reading("150").build());

        verify(telemetryTenantRepository).findLatestCompletedFlowReadingBeforeDate(eq(SCHEMA), eq(SELECTED), eq(OPERATOR), any());
    }
}
