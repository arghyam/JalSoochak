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
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.arghyam.jalsoochak.analytics.repository.DimDateRepository;
import org.arghyam.jalsoochak.analytics.repository.DimOperatorAttendanceRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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

    @InjectMocks
    private FactServiceImpl service;

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
        event.setChannel(2);
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

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDate()).isEqualTo(LocalDate.now());
        assertThat(captor.getValue().getOutageReason()).isEqualTo("no_electricity");
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
        assertThat(captor.getValue().getEscalationType()).isEqualTo("consecutive_override_5_days");
        assertThat(captor.getValue().getResolutionStatus()).isEqualTo(0);
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
        assertThat(saved.getEscalationType()).isEqualTo("no_supply");
        assertThat(saved.getCorrelationId())
                .isEqualTo(service.buildCorrelationId(EscalationType.NO_WATER_SUPPLY, 21, 1, 11));
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
        assertThat(escCaptor.getValue().getEscalationType()).isEqualTo("no_submission");
        assertThat(escCaptor.getValue().getCorrelationId())
                .isEqualTo(service.buildCorrelationId(EscalationType.NO_SUBMISSION, 21, 1, 11));

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getUserId()).isEqualTo(21); // operator userId
        assertThat(anomalyCaptor.getValue().getConsecutiveDaysMissed()).isEqualTo(5);
        assertThat(anomalyCaptor.getValue().getType()).isEqualTo("no_submission");
    }

    @Test
    void ingestTenantEscalation_missingAnomalyType_fallsBackToNoSubmission() {
        TenantEscalationEvent event = buildEscalationEvent(buildOp(21, 5, "corr-fb", "11"));
        // anomalyType not set — simulates events from older tenant-service versions
        when(dimTenantRepository.existsById(1)).thenReturn(true);

        service.ingestTenantEscalation(event);

        ArgumentCaptor<FactEscalation> escCaptor = ArgumentCaptor.forClass(FactEscalation.class);
        verify(escalationRepository, times(1)).save(escCaptor.capture());
        assertThat(escCaptor.getValue().getEscalationType()).isEqualTo("no_submission");

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getType()).isEqualTo("no_submission");
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

        ArgumentCaptor<Anomaly> anomalyCaptor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository, times(1)).save(anomalyCaptor.capture());
        assertThat(anomalyCaptor.getValue().getConsecutiveDaysMissed()).isNull();
        assertThat(anomalyCaptor.getValue().getPreviousReadingDate()).isNull();
        assertThat(anomalyCaptor.getValue().getReason()).contains("never uploaded");
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
        assertThat(captor.getValue().getLastWaterSupplyDate()).isEqualTo(LocalDate.now());
    }
}
