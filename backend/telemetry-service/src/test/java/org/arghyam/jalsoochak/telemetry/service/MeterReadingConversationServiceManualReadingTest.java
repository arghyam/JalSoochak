package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryFlowReadingDetails;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryPendingMeterChangeRecord;
import org.arghyam.jalsoochak.telemetry.repository.FlowReadingVersion;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimitFixtures;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeterReadingConversationServiceManualReadingTest {

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

    /**
     * LOCATION-AFFINITY: the manual path runs the boundary check too, because an operator who types
     * the reading in — usually after an unreadable photo — would otherwise escape it.
     */
    @Mock
    private org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityService locationAffinityService;

    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Mock
    private ReadingChannelResolver readingChannelResolver;

    @Mock
    private ReadingRepublisher readingRepublisher;

    @Spy
    private SubmittedValueCapture submittedValueCapture = new SubmittedValueCapture();

    @Mock
    private PduDayLimit pduDayLimit;

    @InjectMocks
    private MeterReadingConversationService service;

    @BeforeEach
    void defaultChannel() {
        PduDayLimitFixtures.allowsEveryRun(pduDayLimit);
        lenient().when(readingChannelResolver.resolve(anyString(), anyString())).thenReturn(ReadingChannel.BFM);
    }

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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
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
                any()
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

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("123"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
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
                any()
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

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 77L, new BigDecimal("123"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void manualReadingReenteringTodaysResolvedValueKeepsRolloverProvenance() {
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());

        // Today's row already holds the resolver's value (150); the operator re-enters the same number.
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L,
                        "bfm-1",
                        1L,
                        new BigDecimal("250"),
                        new BigDecimal("150")
                )));

        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                any()
        );

        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("150")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        // Same value as the stored resolved reading → provenance left untouched (null source), so the row
        // keeps SOURCE_ROLLOVER_RESOLVED rather than being overwritten to MANUAL.
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("150"), 1L, null, "m3", ReportingChannel.WHATSAPP);
    }

    @Test
    void manualReadingAcceptsWhenLowerThanPreviousAndMeterNotReplaced() {
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("200"), ReadingTime.now().minusDays(1))));
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L,
                        "bfm-1",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("100")
                .isMeterReplaced(false)
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("100"), resp.getMeterReading());
        assertEquals("bfm-1", resp.getCorrelationId());

        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("100"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
        verify(telemetryTenantRepository, never()).updateMeterChangeReason(anyString(), anyLong(), anyString(), anyLong());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                anyString(),
                argThat(anomaly -> anomaly != null && anomaly.type() == AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS)
        );
        verify(telemetryEventPublisher, never()).publishAnomalyRecorded(
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
        , any());
    }

    @Test
    void manualReadingAcceptsWhenImpliedQuantityBelowTenantThreshold() {
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());

        // Thresholds: undersupply 50% of water norm (oversupply 0%).
        when(tenantConfigRepository.findConfigValue(1, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(1, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.of("{\"undersupplyThresholdPercent\":50.0,\"oversupplyThresholdPercent\":0.0}"));
        when(tenantConfigRepository.findConfigValue(1, "WATER_NORM"))
                .thenReturn(Optional.of("{\"value\":\"100\"}"));
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        55L,
                        "bfm-threshold",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("40") // min allowed = 50
                .isMeterReplaced(false)
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("40"), resp.getMeterReading());
        assertEquals("bfm-threshold", resp.getCorrelationId());

        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                anyString(),
                argThat(anomaly -> anomaly != null && anomaly.type() == AnomalyConstants.TYPE_LOW_WATER_SUPPLY)
        );
        verify(telemetryEventPublisher, never()).publishOutageOrNonSubmissionReason(
                ArgumentMatchers.eq(1),
                ArgumentMatchers.eq(10L),
                ArgumentMatchers.eq(1L),
                any(),
                ArgumentMatchers.eq(AnomalyConstants.TYPE_LOW_WATER_SUPPLY),
                ArgumentMatchers.anyString()
        );

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 55L, new BigDecimal("40"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
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
                argThat(anomaly -> anomaly != null && anomaly.type() == AnomalyConstants.TYPE_OVER_WATER_SUPPLY)
        );

        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any(), any());
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
        verify(telemetryTenantRepository, never()).updateFlowReadingChannel(anyString(), anyLong(), any());
        verify(readingRepublisher, never()).republish(anyString(), any(), anyLong());
    }

    @Test
    void manualReadingWithNoRowForTodayCreatesARowTaggedManual() {
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());

        // Nothing recorded for today yet: the manual value opens the row.
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyString(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(new FlowReadingVersion(4242L, null));

        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                any()
        );

        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());

        // extracted_reading keeps its 0 sentinel (nothing extracted this number) and the row must not
        // keep confirmed_reading_source at its DEFAULT 0 (= AS_EXTRACTED), which would claim the AI
        // picked a value it never saw. Both go in through the @Transactional persist helper, so the row
        // can never commit without its provenance marker.
        verify(telemetryTenantRepository).persistFlowReadingWithTracking(ArgumentMatchers.eq("tenant_test"),
                ArgumentMatchers.isNull(), ArgumentMatchers.eq(10L), ArgumentMatchers.eq(1L), any(),
                ArgumentMatchers.eq(BigDecimal.ZERO), ArgumentMatchers.eq(new BigDecimal("123")), anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.eq(""), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(IngestionSource.NORMAL), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq(RolloverResolutionService.SOURCE_MANUAL), ArgumentMatchers.isNull(),
                // The operator's channel, and its standard unit: a typed-in value is never converted.
                ArgumentMatchers.eq(ReadingChannel.BFM), ArgumentMatchers.eq("m3"), ArgumentMatchers.eq(ReportingChannel.WHATSAPP));
        // The two-statement route is what allowed a row to commit unmarked if the second write failed.
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(),
                any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
        verify(telemetryTenantRepository, never()).applyConfirmedReadingSource(anyString(), anyLong(), anyInt(), any());
    }

    @Test
    void manualReadingReportsAFailureWhenTheRowCannotBePersisted() {
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.empty());

        // The provenance write is inside the persist transaction, so its failure rolls the insert back
        // and surfaces here as one failed call — never as a committed row missing its marker.
        when(telemetryTenantRepository.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyString(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("confirmed_reading_source write failed"));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        // No anomaly, no escalation, no confirmation template lookup once the reading did not land.
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(anyString(), any());
        verify(telemetryTenantRepository, never()).applyConfirmedReadingSource(anyString(), anyLong(), anyInt(), any());
        // Nor an event: there is no stored reading to publish.
        verify(telemetryTenantRepository, never()).updateFlowReadingChannel(anyString(), anyLong(), any());
        verify(readingRepublisher, never()).republish(anyString(), any(), anyLong());
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshotBeforeDate("tenant_test", 10L, ReadingChannel.BFM, ReadingTime.today(), null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
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
                any()
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

        verify(telemetryTenantRepository).findLatestConfirmedReadingSnapshotBeforeDate("tenant_test", 10L, ReadingChannel.BFM, ReadingTime.today(), null);
        verify(telemetryTenantRepository, never()).findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null);
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("1000"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("200"), ReadingTime.now().minusDays(1))));

        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
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
                any()
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

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 55L, new BigDecimal("100"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
        verify(telemetryTenantRepository).updateMeterChangeReason("tenant_test", 55L, "METER_REPLACED", 1L);
        verify(telemetryTenantRepository, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L,
                        "bfm-1",
                        1L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO
                )));
        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        // One reading of the clock: five separate calls could straddle an IST midnight and hand back a
        // run of dates that is not consecutive, which is exactly what this test needs it to be.
        LocalDate today = ReadingTime.today();
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        today,
                        today.minusDays(1),
                        today.minusDays(2),
                        today.minusDays(3),
                        today.minusDays(4)
                ));

        doNothing().when(telemetryTenantRepository).createTenantAnomalyRecord(
                anyString(),
                any()
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
        , any());
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
        , any());
    }

    @Test
    void manualReadingRunsTheBoundaryCheckAgainstTheRowItWroteTo() {
        // An operator reaching manual entry has usually just failed an image submission, so this is
        // the path most likely to carry an out-of-boundary location — and it never touches
        // BfmReadingService.createReading, where the check for every other path lives.
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
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.BFM, null))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L, "bfm-1", 1L, BigDecimal.ZERO, BigDecimal.ZERO)));
        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString()))
                .thenReturn(Optional.empty());

        service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        org.mockito.ArgumentCaptor<org.arghyam.jalsoochak.telemetry.service.location.ReadingSubmission> submission =
                org.mockito.ArgumentCaptor.forClass(
                        org.arghyam.jalsoochak.telemetry.service.location.ReadingSubmission.class);
        verify(locationAffinityService).recordMismatchIfAny(
                org.mockito.ArgumentMatchers.eq("tenant_test"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                submission.capture(),
                // No coordinates on a manual reading: they arrived in an earlier /location message
                // and are already on the row, which the service reads back.
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(
                        org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityService.Path.MANUAL_READING));

        assertEquals(99L, submission.getValue().readingId());
        assertEquals(ReadingTime.today(), submission.getValue().readingDate());
    }

    /**
     * The three branches below each end with a stored reading, and analytics must receive every one
     * of them: before, the manual path never published, so the day had no fact row and no water
     * quantity. Each publishes the row it wrote, once, after the value and the channel are stored.
     */
    @Test
    void manualReadingOnAPendingMeterChangeRowPublishesThatRow() {
        stubAcceptedManualReading();
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.of(new TelemetryPendingMeterChangeRecord(
                        88L, "mc-1", 1L, new BigDecimal("140"))));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("150")
                .build());

        assertEquals(true, resp.isSuccess());
        InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
        order.verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 88L, new BigDecimal("150"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3", ReportingChannel.WHATSAPP);
        order.verify(telemetryTenantRepository).updateFlowReadingChannel("tenant_test", 88L, ReadingChannel.BFM);
        order.verify(readingRepublisher).republish("tenant_test", 1, 88L);
        verify(readingRepublisher, times(1)).republish(anyString(), any(), anyLong());
    }

    @Test
    void manualReadingOnTodaysRowPublishesThatRowOnTheOperatorsChannel() {
        stubAcceptedManualReading();
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L, "bfm-1", 1L, BigDecimal.ZERO, BigDecimal.ZERO)));
        // The channel is the operator's stored preference, as on the photo path.
        when(readingChannelResolver.resolve("tenant_test", "919999999999")).thenReturn(ReadingChannel.ELM);

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertEquals(true, resp.isSuccess());
        InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
        // A typed-in value is in the channel's standard unit.
        order.verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("123"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "kW.h", ReportingChannel.WHATSAPP);
        order.verify(telemetryTenantRepository).updateFlowReadingChannel("tenant_test", 99L, ReadingChannel.ELM);
        order.verify(readingRepublisher).republish("tenant_test", 1, 99L);
        verify(readingRepublisher, times(1)).republish(anyString(), any(), anyLong());
    }

    @Test
    void manualReadingThatOpensTheRowPublishesTheNewRow() {
        stubAcceptedManualReading();
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyString(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenReturn(new FlowReadingVersion(4242L, null));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("123")
                .build());

        assertEquals(true, resp.isSuccess());
        InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
        // The channel goes in with the insert, so no separate channel write follows it.
        order.verify(telemetryTenantRepository).persistFlowReadingWithTracking(anyString(), any(), anyLong(),
                anyLong(), any(), any(), any(), anyString(), any(), anyString(), any(), anyInt(), any(), any(),
                any(), any(), any(), ArgumentMatchers.eq(ReadingChannel.BFM), ArgumentMatchers.eq("m3"), ArgumentMatchers.eq(ReportingChannel.WHATSAPP));
        order.verify(readingRepublisher).republish("tenant_test", 1, 4242L);
        verify(telemetryTenantRepository, never()).updateFlowReadingChannel(any(), any(), any());
        verify(readingRepublisher, times(1)).republish(anyString(), any(), anyLong());
    }

    @Test
    void manualReadingOfAPduRunLongerThanADayIsRejectedWithoutWritingAnything() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );
        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("hi");
        when(localizationService.normalizeLanguageKey("hi")).thenReturn("hindi");
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(readingChannelResolver.resolve("tenant_test", "919999999999")).thenReturn(ReadingChannel.PDU);
        when(localizationService.localizeMessage(SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE, "hindi"))
                .thenReturn("localised");

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("1441")
                .build());

        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());
        assertEquals("localised", resp.getMessage());
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any(), any());
        verify(telemetryTenantRepository, never()).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyString(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(anyString(), any());
        verify(readingRepublisher, never()).republish(anyString(), any(), anyLong());
    }

    @Test
    void manualReadingOfAPduRunTakingItsDayPastTheLimitIsRejectedWithoutWritingAnything() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );
        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("hi");
        when(localizationService.normalizeLanguageKey("hi")).thenReturn("hindi");
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L, "pdu-1", 1L, BigDecimal.ZERO, new BigDecimal("300"))));
        when(readingChannelResolver.resolve("tenant_test", "919999999999")).thenReturn(ReadingChannel.PDU);
        doReturn(Optional.empty()).when(pduDayLimit).writeWithinLimit(any(), any(), any(), any(), any(), any());
        when(localizationService.localizeMessage(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE, "hindi"))
                .thenReturn("localised");

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("600")
                .build());

        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());
        assertEquals("localised", resp.getMessage());
        // The value is written over today's row, so that row's old minutes don't count.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Long>> replaced = ArgumentCaptor.forClass(Supplier.class);
        verify(pduDayLimit).writeWithinLimit(ArgumentMatchers.eq("tenant_test"), ArgumentMatchers.eq(10L),
                ArgumentMatchers.eq(ReadingTime.today()), ArgumentMatchers.eq(new BigDecimal("600")),
                replaced.capture(), any());
        assertEquals(99L, replaced.getValue().get());
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any(), any());
        verify(telemetryTenantRepository, never()).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(), any(), any(), anyString(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(readingRepublisher, never()).republish(anyString(), any(), anyLong());
    }

    @Test
    void manualReadingOfAPduRunHasNoBaselineAndSkipsTheMaximumCheck() {
        stubAcceptedManualReading();
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L, "pdu-1", 1L, BigDecimal.ZERO, BigDecimal.ZERO)));
        when(readingChannelResolver.resolve("tenant_test", "919999999999")).thenReturn(ReadingChannel.PDU);

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("1440")
                .build());

        assertEquals(true, resp.isSuccess());
        // A run is not a running total, so there is nothing earlier to compare it with.
        verify(telemetryTenantRepository, never()).findLatestConfirmedReadingSnapshot(any(), any(), any(), any());
        verify(telemetryTenantRepository, never()).findLatestConfirmedReadingSnapshotBeforeDate(any(), any(), any(), any(), any());
        // The maximum is a water volume measured against a meter total, so its config isn't even read.
        verify(tenantConfigRepository, never()).findConfigValue(anyInt(), ArgumentMatchers.eq("WATER_NORM"));
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("1440"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "min", ReportingChannel.WHATSAPP);
        verify(readingRepublisher).republish("tenant_test", 1, 99L);
    }

    @Test
    void manualReadingOnElmIsComparedWithElmReadingsOnlyAndSkipsTheMaximumCheck() {
        stubAcceptedManualReading();
        when(telemetryTenantRepository.findLatestPendingMeterChangeRecord("tenant_test", 10L, 1L))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findLatestFlowReadingForDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryFlowReadingDetails(
                        99L, "elm-1", 1L, BigDecimal.ZERO, BigDecimal.ZERO)));
        when(readingChannelResolver.resolve("tenant_test", "919999999999")).thenReturn(ReadingChannel.ELM);
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_test", 10L, ReadingChannel.ELM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("4800"), ReadingTime.now().minusDays(1))));

        CreateReadingResponse resp = service.manualReadingMessage(ManualReadingRequest.builder()
                .contactId("919999999999")
                .manualReading("4821.5")
                .build());

        assertEquals(true, resp.isSuccess());
        verify(tenantConfigRepository, never()).findConfigValue(anyInt(), ArgumentMatchers.eq("WATER_NORM"));
        // The override anomaly records the ELM baseline, not whatever reading came last on the scheme.
        verify(telemetryTenantRepository).createTenantAnomalyRecord(ArgumentMatchers.eq("tenant_test"),
                argThat(anomaly -> anomaly != null
                        && anomaly.type() == AnomalyConstants.TYPE_MANUAL_OVERRIDE
                        && new BigDecimal("4800").equals(anomaly.previousReading())));
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 99L, new BigDecimal("4821.5"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "kW.h", ReportingChannel.WHATSAPP);
    }

    /** Everything an accepted manual reading needs apart from the row it lands on. */
    private void stubAcceptedManualReading() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );
        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
        lenient().when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot(
                        ArgumentMatchers.eq("tenant_test"), ArgumentMatchers.eq(10L), any(), ArgumentMatchers.isNull()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.countAnomaliesByTypeForToday(anyString(), anyLong(), anyLong(), anyInt()))
                .thenReturn(0);
        when(telemetryTenantRepository.findAnomalyDatesByType(anyString(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(tenantConfigRepository.findManualReadingConfirmationTemplate(anyInt(), anyString()))
                .thenReturn(Optional.empty());
    }
}
