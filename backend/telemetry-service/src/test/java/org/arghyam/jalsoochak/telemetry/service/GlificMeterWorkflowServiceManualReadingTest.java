package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryFlowReadingDetails;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificMeterWorkflowServiceManualReadingTest {

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
    private TelemetryEventPublisher telemetryEventPublisher;

    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private GlificMeterWorkflowService service;

    @Test
    void manualReadingUpdatesTodaysReadingByUpdatingConfirmedOnly() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L,
                        "bfm-1",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));

        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyString(),
                anyInt()
        );

        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("123"), resp.getMeterReading());
        assertEquals("bfm-1", resp.getCorrelationId());

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("123"), 1L);
        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void manualReadingUpdatesTodaysReadingAndOnlySetsConfirmedWhenExtractedAlreadyPresent() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        77L,
                        "bfm-2",
                        1L,
                        new BigDecimal("111"),
                        BigDecimal.ZERO
                )));

        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyString(),
                anyInt()
        );

        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("123"), resp.getMeterReading());
        assertEquals("bfm-2", resp.getCorrelationId());

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 77L, new BigDecimal("123"), 1L);
        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void manualReadingRejectsWhenLowerThanPreviousAndMeterNotReplaced() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("200"), LocalDateTime.now().minusDays(1))));
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyString(),
                anyInt()
        );

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("100")
                .isMeterReplaced(false)
                .build());

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());
        assertEquals(new BigDecimal("100"), resp.getMeterReading());
        assertEquals(new BigDecimal("200"), resp.getLastConfirmedReading());
        assertEquals(
                "Reading rejected because it is below the last confirmed reading. Submitted: 100. Last confirmed: 200.",
                resp.getMessage()
        );

        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).updateMeterChangeReason(anyString(), anyLong(), anyString(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any());
        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                ArgumentMatchers.eq(AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS),
                anyString(),
                ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryEventPublisher).publishAnomalyRecorded(
                ArgumentMatchers.eq(1),
                ArgumentMatchers.eq(AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS),
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.eq(10L),
                any(),
                any(),
                ArgumentMatchers.eq(new BigDecimal("100")),
                ArgumentMatchers.eq(0),
                any(),
                any(),
                ArgumentMatchers.eq(0),
                anyString(),
                ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN),
                anyString()
        );
    }

    @Test
    void manualReadingRejectsWhenImpliedQuantityBelowTenantThreshold() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());

        // Snapshot is optional for this validation; it's only used as context in the anomaly record.
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.empty());

        // Thresholds: undersupply 50% of water norm (oversupply 0%).
        when(tenantConfigRepository.findConfigValue(1, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(1, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.of("{\"undersupplyThresholdPercent\":50.0,\"oversupplyThresholdPercent\":0.0}"));
        when(tenantConfigRepository.findConfigValue(1, "WATER_NORM"))
                .thenReturn(Optional.of("{\"value\":\"100\"}"));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("40") // min allowed = 50
                .isMeterReplaced(false)
                .build());

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                ArgumentMatchers.eq(AnomalyConstants.TYPE_LOW_WATER_SUPPLY),
                anyString(),
                ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryEventPublisher).publishOutageOrNonSubmissionReason(
                ArgumentMatchers.eq(1),
                ArgumentMatchers.eq(10L),
                ArgumentMatchers.eq(1L),
                any(),
                ArgumentMatchers.eq(AnomalyConstants.TYPE_LOW_WATER_SUPPLY)
        );

        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void manualReadingRejectsWhenReadingAboveTenantOversupplyThreshold() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.empty());

        // Oversupply 10% above water norm.
        when(tenantConfigRepository.findConfigValue(1, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.of("{\"undersupplyThresholdPercent\":0.0,\"oversupplyThresholdPercent\":10.0}"));
        when(tenantConfigRepository.findConfigValue(1, "WATER_NORM"))
                .thenReturn(Optional.of("{\"value\":\"100\"}"));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("120") // max allowed = 110
                .isMeterReplaced(false)
                .build());

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());

        verify(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                ArgumentMatchers.eq(AnomalyConstants.TYPE_OVER_WATER_SUPPLY),
                anyString(),
                ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN)
        );

        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void manualReadingWhenNoPreviousSnapshotBeforeTodayAllowsSubmission() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());

        // No confirmed reading before today => allow today's manual reading.
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshotBeforeDate("tenant_test", 10L, LocalDate.now(), null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L,
                        "bfm-1",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));

        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyString(),
                anyInt()
        );

        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("1000")
                .isManualReading(false)
                .isMeterReplaced(false)
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("1000"), resp.getMeterReading());
        assertEquals("bfm-1", resp.getCorrelationId());

        verify(telemetryTenantRepository).findLatestConfirmedReadingSnapshotBeforeDate("tenant_test", 10L, LocalDate.now(), null);
        verify(telemetryTenantRepository, never()).findLatestConfirmedReadingSnapshot("tenant_test", 10L, null);
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("1000"), 1L);
    }

    @Test
    void manualReadingAcceptsLowerReadingWhenMeterReplacedAndRecordsReason() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("200"), LocalDateTime.now().minusDays(1))));

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        55L,
                        "bfm-55",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));

        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyString(),
                anyInt()
        );

        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("100")
                .isMeterReplaced(true)
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("100"), resp.getMeterReading());
        assertEquals("bfm-55", resp.getCorrelationId());

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 55L, new BigDecimal("100"), 1L);
        verify(telemetryTenantRepository).updateMeterChangeReason("tenant_test", 55L, "METER_REPLACED", 1L);
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void manualReadingAfterFiveConsecutiveOverridesPublishesEscalationEvent() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findSubDivisionalOfficerUserIdsForScheme("tenant_test", 10L)).thenReturn(List.of());
        when(telemetryTenantRepository.findSectionOfficerUserIdsForScheme("tenant_test", 10L)).thenReturn(List.of(99L, 100L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, null))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L,
                        "bfm-1",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));
        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        LocalDate.now(),
                        LocalDate.now().minusDays(1),
                        LocalDate.now().minusDays(2),
                        LocalDate.now().minusDays(3),
                        LocalDate.now().minusDays(4)
                ));

        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                anyInt(),
                anyString(),
                anyInt()
        );
        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());

        verify(telemetryEventPublisher, times(2)).publishEscalationCreated(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(10L),
                ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.TYPE_CONSECUTIVE_OVERRIDE_5_DAYS),
                org.mockito.ArgumentMatchers.eq("Manual overrides recorded for five or more consecutive days."),
                org.mockito.ArgumentMatchers.eq("bfm-1"),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN),
                org.mockito.ArgumentMatchers.isNull()
        );
        verify(telemetryEventPublisher, times(1)).publishAnomalyRecorded(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.TYPE_MANUAL_OVERRIDE),
                ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(10L),
                any(),
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                anyInt(),
                org.mockito.ArgumentMatchers.eq("Manual reading submitted as override."),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN),
                any()
        );
        verify(telemetryEventPublisher, never()).publishAnomalyRecorded(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.TYPE_CONSECUTIVE_OVERRIDE_5_DAYS),
                anyLong(),
                anyLong(),
                any(),
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                anyInt(),
                anyString(),
                anyInt(),
                any()
        );
    }
}
