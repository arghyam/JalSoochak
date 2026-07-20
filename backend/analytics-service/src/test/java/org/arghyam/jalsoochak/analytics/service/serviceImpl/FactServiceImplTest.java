package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.constant.EscalationType;
import org.arghyam.jalsoochak.analytics.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.analytics.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemePerformanceEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.analytics.entity.Anomaly;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.arghyam.jalsoochak.analytics.repository.DimDateRepository;
import org.arghyam.jalsoochak.analytics.repository.DimOperatorAttendanceRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.arghyam.jalsoochak.analytics.service.water.BfmWaterQuantityCalculator;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityCalculatorRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactServiceImplTest {

    @Mock
    private FactMeterReadingRepository meterReadingRepository;
    @Mock
    private FactWaterQuantityRepository waterQuantityRepository;
    @Mock
    private FactEscalationRepository escalationRepository;
    @Mock
    private FactSchemePerformanceRepository schemePerformanceRepository;
    @Mock
    private AnomalyRepository anomalyRepository;
    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private DimDateRepository dimDateRepository;
    @Mock
    private DimOperatorAttendanceRepository dimOperatorAttendanceRepository;
    @Mock
    private org.arghyam.jalsoochak.analytics.repository.SubmissionAttemptRepository submissionAttemptRepository;

    // Real registry (with the default BFM calculator) so ingestMeterReading runs the
    // historical cumulative-delta calculation. @Spy is injected by @InjectMocks.
    @Spy
    private WaterQuantityCalculatorRegistry waterQuantityCalculatorRegistry =
            new WaterQuantityCalculatorRegistry(List.of(new BfmWaterQuantityCalculator()));

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private FactServiceImpl service;

    @Test
    void ingestSubmissionRejected_resolvesSchemeAndInserts() {
        org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent event =
                org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent.builder()
                        .eventType("SUBMISSION_REJECTED")
                        .tenantId(17)
                        .submittedStateSchemeId("6121849")
                        .submittedPhoneHash("phv")
                        .reason("validation: phone must not be blank")
                        .attemptedAt("2026-07-05T10:15:00")
                        .build();
        when(submissionAttemptRepository.resolveScheme(17, "6121849", null))
                .thenReturn(Optional.of(new int[]{555, 17}));

        service.ingestSubmissionRejected(event);

        verify(submissionAttemptRepository).insert(
                org.mockito.ArgumentMatchers.eq(17),
                org.mockito.ArgumentMatchers.eq(555),
                org.mockito.ArgumentMatchers.eq("6121849"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("phv"),
                org.mockito.ArgumentMatchers.eq("validation: phone must not be blank"),
                org.mockito.ArgumentMatchers.eq(LocalDateTime.parse("2026-07-05T10:15:00")));
    }

    @Test
    void ingestSubmissionRejected_unresolvedScheme_insertsNullSchemeId() {
        org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent event =
                org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent.builder()
                        .eventType("SUBMISSION_REJECTED")
                        .tenantId(17)
                        .submittedStateSchemeId("99999999")
                        .reason("validation")
                        .attemptedAt("2026-07-05T10:15:00")
                        .build();
        when(submissionAttemptRepository.resolveScheme(17, "99999999", null)).thenReturn(Optional.empty());

        service.ingestSubmissionRejected(event);

        verify(submissionAttemptRepository).insert(
                org.mockito.ArgumentMatchers.eq(17),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("99999999"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("validation"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class));
    }

    @Test
    void ingestMeterReading_mapsAndSavesFactEntity() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setExtractedReading(100);
        event.setConfirmedReading(95);
        event.setConfidence(90);
        event.setImageUrl("img");
        event.setReadingAt("2026-01-01T10:15:00");
        event.setChannel(1);
        event.setReadingDate("2026-01-01");
        event.setSubmissionStatus(1);
        event.setReadingType(0);
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(dimOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any()))
                .thenReturn(false);
        when(meterReadingRepository.findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(meterReadingRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(1);
        assertThat(captor.getValue().getSchemeId()).isEqualTo(11);
        assertThat(captor.getValue().getReadingAt()).isEqualTo(LocalDateTime.parse("2026-01-01T10:15:00"));
        assertThat(captor.getValue().getReadingDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(captor.getValue().getSubmissionStatus()).isEqualTo(1);
        assertThat(captor.getValue().getReadingType()).isEqualTo(0);
    }

    @Test
    void ingestMeterReading_whenComputedWaterQuantityIsNegative_storesZero() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setConfirmedReading(95);
        event.setReadingAt("2026-01-02T10:15:00");
        event.setReadingDate("2026-01-02");
        event.setSubmissionStatus(1);
        event.setReadingType(0);

        FactMeterReading previousDayReading = FactMeterReading.builder()
                .confirmedReading(100)
                .build();

        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(dimOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any()))
                .thenReturn(false);
        when(meterReadingRepository.findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDesc(any(), any(), any()))
                .thenReturn(Optional.of(previousDayReading));
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.ingestMeterReading(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository).save(captor.capture());
        assertThat(captor.getValue().getWaterQuantity()).isEqualTo(0);
    }

    @Test
    void ingestMeterReading_resolvesWaterQuantityCalculatorByEventChannel() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setConfirmedReading(150);
        event.setReadingAt("2026-01-02T10:15:00");
        event.setReadingDate("2026-01-02");
        event.setSubmissionStatus(1);
        event.setChannel(ReadingChannel.ELM.getCode());

        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(dimOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any()))
                .thenReturn(false);

        service.ingestMeterReading(event);

        // The per-channel calculator is selected using the event's channel code; ELM has no
        // registered calculator here, so the water quantity is skipped rather than mis-derived as BFM.
        verify(waterQuantityCalculatorRegistry).resolve(ReadingChannel.ELM.getCode());
        verify(waterQuantityRepository, never()).save(any());
    }

    @Test
    void ingestWaterQuantity_whenInvalidDate_fallsBackToToday() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setWaterQuantity(120);
        event.setSubmissionStatus(1);
        event.setOutageReason("no_electricity");
        event.setDate("invalid-date");
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        assertThat(captor.getValue().getOutageReason()).isEqualTo("no_electricity");
    }

    @Test
    void ingestWaterQuantity_whenExistingRecord_updatesExistingRow() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(22);
        event.setWaterQuantity(200);
        event.setSubmissionStatus(1);
        event.setDate("2026-01-05");

        FactWaterQuantity existing = FactWaterQuantity.builder()
                .id(99L)
                .tenantId(1)
                .schemeId(11)
                .userId(10)
                .waterQuantity(100)
                .submissionStatus(0)
                .date(LocalDate.of(2026, 1, 5))
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        when(dimDateRepository.findByFullDate(LocalDate.of(2026, 1, 5))).thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                1, 11, LocalDate.of(2026, 1, 5)))
                .thenReturn(Optional.of(existing));

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(99L);
        assertThat(captor.getValue().getUserId()).isEqualTo(22);
        assertThat(captor.getValue().getWaterQuantity()).isEqualTo(200);
        assertThat(captor.getValue().getSubmissionStatus()).isEqualTo(1);
    }

    @Test
    void ingestWaterQuantity_whenIncomingWaterQuantityIsNegative_storesZero() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(22);
        event.setWaterQuantity(-25);
        event.setSubmissionStatus(1);
        event.setDate("2026-01-05");

        when(dimDateRepository.findByFullDate(LocalDate.of(2026, 1, 5))).thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                1, 11, LocalDate.of(2026, 1, 5)))
                .thenReturn(Optional.empty());

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository).save(captor.capture());
        assertThat(captor.getValue().getWaterQuantity()).isEqualTo(0);
    }

    @Test
    void ingestEscalation_mapsAndSavesFactEntity() {
        EscalationEvent event = new EscalationEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setEscalationType(3);
        event.setMessage("msg");
        event.setUserId(21);
        event.setResolutionStatus(0);
        event.setRemark("remark");

        service.ingestEscalation(event);

        ArgumentCaptor<FactEscalation> captor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEscalationType()).isEqualTo("CONSECUTIVE_OVERRIDE_5_DAYS");
        assertThat(captor.getValue().getResolutionStatus()).isEqualTo(0);
    }

    @Test
    void ingestEscalation_neverSubmittedMessage_persistsNoSubmissionLabel() {
        EscalationEvent event = new EscalationEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setEscalationType(EscalationType.NO_WATER_SUPPLY.code);
        event.setMessage("pump_operator has never submitted a reading");
        event.setUserId(21);
        event.setResolutionStatus(1);

        service.ingestEscalation(event);

        ArgumentCaptor<FactEscalation> captor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEscalationType()).isEqualTo("NO_SUBMISSION");
    }

    @Test
    void ingestAnomalyRecorded_forWaterAnomaly_alsoSavesEscalationFact() {
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("uuid-1");
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setType(EscalationType.NO_WATER_SUPPLY.code);
        event.setReason("No water supply");
        event.setStatus(1);

        service.ingestAnomalyRecorded(event);

        verify(anomalyRepository, times(1)).save(any());
        ArgumentCaptor<FactEscalation> captor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(captor.capture());
        FactEscalation saved = captor.getValue();
        assertThat(saved.getEscalationType()).isEqualTo("NO_WATER_SUPPLY");
        assertThat(saved.getCorrelationId())
                .isEqualTo(service.buildCorrelationId(EscalationType.NO_WATER_SUPPLY, 21, 1, 11));
    }

    @Test
    void ingestAnomalyRecorded_forImageAnomaly_savesOnlyAnomaly() {
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("uuid-image-1");
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setType(EscalationType.UNREADABLE_IMAGE.code);
        event.setReason("Unreadable image");
        event.setStatus(1);

        service.ingestAnomalyRecorded(event);

        verify(anomalyRepository, times(1)).save(any());
        verify(escalationRepository, never()).save(any());
    }

    @Test
    void ingestAnomalyRecorded_duplicateUuid_touchesExistingAnomalyAndSkipsInsert() {
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("uuid-image-dup");
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setType(EscalationType.UNREADABLE_IMAGE.code);
        event.setReason("Unreadable image");
        event.setStatus(1);
        when(anomalyRepository.existsByUuid("uuid-image-dup")).thenReturn(true);

        service.ingestAnomalyRecorded(event);

        verify(anomalyRepository, never()).save(any());
        verify(anomalyRepository, times(1)).touchByUuid(org.mockito.ArgumentMatchers.eq("uuid-image-dup"), any());
        verify(escalationRepository, never()).save(any());
    }

    @Test
    void ingestAnomalyRecorded_duplicateOnInsert_touchesExistingAnomaly() {
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("uuid-image-race");
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setType(EscalationType.UNREADABLE_IMAGE.code);
        event.setReason("Unreadable image");
        event.setStatus(1);
        when(anomalyRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        service.ingestAnomalyRecorded(event);

        verify(anomalyRepository, times(1)).save(any());
        verify(anomalyRepository, times(1)).touchByUuid(org.mockito.ArgumentMatchers.eq("uuid-image-race"), any());
        verify(escalationRepository, never()).save(any());
    }

    // ── ingestTenantEscalation ───────────────────────────────────────────────

    private TenantEscalationEvent buildEscalationEvent(TenantEscalationEvent.TenantOperatorEscalationDetail... ops) {
        TenantEscalationEvent event = new TenantEscalationEvent();
        event.setTenantId(1);
        event.setTenantSchema("tenant_mp");
        event.setEscalationLevel(1);
        event.setOfficerId(99L);
        event.setOperators(ops.length == 0 ? List.of() : List.of(ops));
        return event;
    }

    private TenantEscalationEvent.TenantOperatorEscalationDetail buildOp(
            Integer userId, Integer consecutiveDays, String correlationId, String schemeId) {
        return buildOp(userId, consecutiveDays, correlationId, schemeId, null);
    }

    private TenantEscalationEvent.TenantOperatorEscalationDetail buildOp(
            Integer userId, Integer consecutiveDays, String correlationId, String schemeId,
            String lastRecordedBfmDate) {
        TenantEscalationEvent.TenantOperatorEscalationDetail op =
                new TenantEscalationEvent.TenantOperatorEscalationDetail();
        op.setUserId(userId);
        op.setConsecutiveDaysMissed(consecutiveDays);
        op.setCorrelationId(correlationId);
        op.setSchemeId(schemeId);
        op.setName("Test Operator");
        op.setLastRecordedBfmDate(lastRecordedBfmDate);
        return op;
    }

    @Test
    void ingestTenantEscalation_happyPath_savesEscalationAndAnomaly() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-1", "11"));
        event.setAnomalyType("no_submission");
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        ArgumentCaptor<FactEscalation> escCaptor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(escCaptor.capture());
        assertThat(escCaptor.getValue().getUserId()).isEqualTo(99); // officerId
        assertThat(escCaptor.getValue().getSchemeId()).isEqualTo(11);
        assertThat(escCaptor.getValue().getEscalationType()).isEqualTo("NO_SUBMISSION");
        assertThat(escCaptor.getValue().getCorrelationId())
                .isEqualTo(service.buildCorrelationId(EscalationType.NO_SUBMISSION, 21, 1, 11));

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getUserId()).isEqualTo(21); // operator userId
        assertThat(anomalyCaptor.getValue().getConsecutiveDaysMissed()).isEqualTo(5);
        assertThat(anomalyCaptor.getValue().getType()).isEqualTo("NO_SUBMISSION");
    }

    @Test
    void ingestTenantEscalation_missingAnomalyType_fallsBackToNoSubmission() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-fb", "11"));
        // anomalyType not set — simulates events from older tenant-service versions
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        ArgumentCaptor<FactEscalation> escCaptor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(escCaptor.capture());
        assertThat(escCaptor.getValue().getEscalationType()).isEqualTo("NO_SUBMISSION");

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getType()).isEqualTo("NO_SUBMISSION");
    }

    @Test
    void ingestTenantEscalation_whitespaceOnlyAnomalyType_fallsBackToNoSubmission() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-ws", "11"));
        // Set anomalyType to whitespace-only string to exercise isBlank() branch
        event.setAnomalyType("   \t   ");
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        ArgumentCaptor<FactEscalation> escCaptor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(escCaptor.capture());
        assertThat(escCaptor.getValue().getEscalationType()).isEqualTo("NO_SUBMISSION");

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getType()).isEqualTo("NO_SUBMISSION");
    }

    @Test
    void ingestTenantEscalation_nullUserId_skipsRow() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(null, 5, "corr-2", "11"));
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        verify(escalationRepository, never()).save(any());
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void ingestTenantEscalation_nullCorrelationId_skipsRow() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, null, "11"));
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        verify(escalationRepository, never()).save(any());
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void ingestTenantEscalation_blankCorrelationId_skipsRow() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "   ", "11"));
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        verify(escalationRepository, never()).save(any());
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void ingestTenantEscalation_duplicateUniqueConstraintIsSwallowed() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-dup", "11"));
        when(dimTenantRepository.existsById(1)).thenReturn(true);
        when(escalationRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(anomalyRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Both saves throw DataIntegrityViolationException (real JPA behavior); both must be swallowed
        service.ingestTenantEscalation(event);

        verify(escalationRepository, times(1)).save(any());
        verify(anomalyRepository, times(1)).save(any());
    }

    @Test
    void ingestTenantEscalation_unexpectedRuntimeException_propagates() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-fk", "11"));
        when(dimTenantRepository.existsById(1)).thenReturn(true);
        when(escalationRepository.save(any())).thenThrow(new RuntimeException("unexpected db error"));

        assertThrows(RuntimeException.class, () -> service.ingestTenantEscalation(event));
    }

    @Test
    void ingestTenantEscalation_invalidSchemeId_skipsRow() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-3", "not-a-number"));
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        verify(escalationRepository, never()).save(any());
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void ingestTenantEscalation_neverUploadedOperator_savesWithNullDaysAndNeverMessage() {
        TenantEscalationEvent event = buildEscalationEvent(
                buildOp(21, null, "corr-never", "11", FactServiceImpl.LAST_RECORDED_BFM_DATE_NEVER));
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        ArgumentCaptor<FactEscalation> escCaptor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(escCaptor.capture());
        assertThat(escCaptor.getValue().getCorrelationId())
                .isEqualTo(service.buildCorrelationId(EscalationType.NO_SUBMISSION, 21, 1, 11));
        assertThat(escCaptor.getValue().getMessage()).contains("never submitted");
        assertThat(escCaptor.getValue().getEscalationType()).isEqualTo("NO_SUBMISSION");

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getConsecutiveDaysMissed()).isNull();
        assertThat(anomalyCaptor.getValue().getPreviousReadingDate()).isNull();
        assertThat(anomalyCaptor.getValue().getReason()).contains("never uploaded");
    }

    @Test
    void ingestMeterReading_nullSubmissionStatus_defaultsToSubmitted() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setReadingAt("2026-01-01T10:00:00");
        event.setReadingDate("2026-01-01");
        event.setSubmissionStatus(null);
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.of(new org.arghyam.jalsoochak.analytics.entity.DimDate()));
        when(dimOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any())).thenReturn(false);

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(meterReadingRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getSubmissionStatus()).isEqualTo(1); // SUBMITTED
    }

    @Test
    void ingestMeterReading_nullReadingType_defaultsToZero() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setReadingAt("2026-01-01T10:00:00");
        event.setReadingDate("2026-01-01");
        event.setReadingType(null);
        event.setSubmissionStatus(1);
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.of(new org.arghyam.jalsoochak.analytics.entity.DimDate()));
        when(dimOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any())).thenReturn(false);

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(meterReadingRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getReadingType()).isEqualTo(0);
    }

    @Test
    void ingestMeterReading_existingOperatorAttendance_doesNotSaveDuplicate() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setReadingAt("2026-01-01T10:00:00");
        event.setReadingDate("2026-01-01");
        event.setSubmissionStatus(1);
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.of(new org.arghyam.jalsoochak.analytics.entity.DimDate()));
        when(dimOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any())).thenReturn(true);

        service.ingestMeterReading(event);

        verify(dimOperatorAttendanceRepository, never()).save(any());
    }

    @Test
    void ingestAnomalyRecorded_blankUuid_generatesNewUuid() {
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("   ");
        event.setStatus(1);
        event.setType(100); // non-water anomaly type
        when(anomalyRepository.existsByUuid(any())).thenReturn(false);

        service.ingestAnomalyRecorded(event);

        ArgumentCaptor<Anomaly> captor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUuid()).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void ingestAnomalyRecorded_nullStatus_defaultsToOpen() {
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("uuid-null-status");
        event.setStatus(null);
        event.setType(100);
        when(anomalyRepository.existsByUuid("uuid-null-status")).thenReturn(false);

        service.ingestAnomalyRecorded(event);

        ArgumentCaptor<Anomaly> captor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(1); // OPEN
    }

    @Test
    void ingestTenantEscalation_nullOfficerId_skipsEscalationFact() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-no-officer", "11"));
        event.setOfficerId(null);
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        verify(escalationRepository, never()).save(any());
        verify(anomalyRepository, times(1)).save(any()); // anomaly still saved
    }

    @Test
    void ingestSchemePerformance_whenBlankDate_fallsBackToToday() {
        SchemePerformanceEvent event = new SchemePerformanceEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setPerformanceScore(BigDecimal.valueOf(88));
        event.setLastWaterSupplyDate("");

        service.ingestSchemePerformance(event);

        ArgumentCaptor<FactSchemePerformance> captor = ArgumentCaptor.forClass(FactSchemePerformance.class);
        verify(schemePerformanceRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getPerformanceScore()).isEqualByComparingTo(BigDecimal.valueOf(88));
        assertThat(captor.getValue().getLastWaterSupplyDate()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    }
}
