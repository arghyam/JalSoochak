package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.constant.EscalationType;
import org.arghyam.jalsoochak.analytics.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
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
import org.arghyam.jalsoochak.analytics.exception.MalformedEventException;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.arghyam.jalsoochak.analytics.repository.DimDateRepository;
import org.arghyam.jalsoochak.analytics.repository.FactOperatorAttendanceRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactIngestionRepository;
import org.arghyam.jalsoochak.analytics.repository.FactIngestionRepository.SchemeDay;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityRangeReporter;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityRecalculationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactServiceImplTest {

    @Mock
    private FactMeterReadingRepository meterReadingRepository;
    @Mock
    private FactWaterQuantityRepository waterQuantityRepository;
    @Mock
    private FactIngestionRepository factIngestionRepository;
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
    private FactOperatorAttendanceRepository factOperatorAttendanceRepository;
    @Mock
    private org.arghyam.jalsoochak.analytics.repository.SubmissionAttemptRepository submissionAttemptRepository;

    @Mock
    private WaterQuantityRecalculationService waterQuantityRecalculationService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private FactServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FactServiceImpl(
                meterReadingRepository,
                waterQuantityRepository,
                factIngestionRepository,
                escalationRepository,
                schemePerformanceRepository,
                anomalyRepository,
                dimTenantRepository,
                dimDateRepository,
                factOperatorAttendanceRepository,
                submissionAttemptRepository,
                waterQuantityRecalculationService,
                new WaterQuantityRangeReporter(meterRegistry, 100_000L),
                meterRegistry);
    }

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
    void ingestMeterReading_carriesTheSubmissionCorrelationIdIntoTheFactRow() {
        // ANOMALY-SUBMISSION-LINK: without this the warehouse has no counterpart for an anomaly's
        // submission_correlation_id, and the two can only be matched by scheme and day.
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setExtractedReading(m3("100.4"));
        event.setConfirmedReading(m3("95.7"));
        event.setReadingAt("2026-01-01T10:15:00");
        event.setReadingDate("2026-01-01");
        event.setCorrelationId("flow-corr-77");
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(factOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any()))
                .thenReturn(false);

        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository).upsertMeterReading(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("flow-corr-77");
    }

    @Test
    void ingestMeterReading_fromAnOlderTelemetryLeavesTheCorrelationIdNull() {
        // The field is additive: this service is deployed first and must take events that predate it.
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setExtractedReading(m3("100.4"));
        event.setConfirmedReading(m3("95.7"));
        event.setReadingAt("2026-01-01T10:15:00");
        event.setReadingDate("2026-01-01");
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(factOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any()))
                .thenReturn(false);

        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository).upsertMeterReading(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isNull();
    }

    @Test
    void ingestAnomalyRecorded_storesTheSubmissionLinkWithoutDisturbingTheDedupKey() {
        // ANOMALY-SUBMISSION-LINK: the two ids answer different questions and must both survive —
        // correlationId is what uuid dedup is keyed on, submissionCorrelationId is the pointer.
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("anom-uuid-1");
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setType(10);
        event.setStatus(1);
        event.setReason("Submitted reading implies an implausible daily water supply for this scheme.");
        event.setCorrelationId("dedup-key");
        event.setSubmissionCorrelationId("flow-corr-77");
        when(anomalyRepository.existsByUuid("anom-uuid-1")).thenReturn(false);

        service.ingestAnomalyRecorded(event);

        ArgumentCaptor<Anomaly> captor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository).save(captor.capture());
        assertThat(captor.getValue().getSubmissionCorrelationId()).isEqualTo("flow-corr-77");
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("dedup-key");
    }

    @Test
    void ingestAnomalyRecorded_withNoSubmissionBehindItLeavesTheLinkNull() {
        // Type 9 NO_SUBMISSION: nothing was submitted, so there is nothing to point at. The link
        // must stay NULL rather than be synthesised the way correlationId can be.
        AnomalyEvent event = new AnomalyEvent();
        event.setUuid("anom-uuid-2");
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setType(9);
        event.setStatus(1);
        event.setReason("Meter not working.");
        event.setCorrelationId("issue-report-1");
        when(anomalyRepository.existsByUuid("anom-uuid-2")).thenReturn(false);

        service.ingestAnomalyRecorded(event);

        ArgumentCaptor<Anomaly> captor = ArgumentCaptor.forClass(Anomaly.class);
        verify(anomalyRepository).save(captor.capture());
        assertThat(captor.getValue().getSubmissionCorrelationId()).isNull();
    }

    @Test
    void ingestMeterReading_mapsAndSavesFactEntity() {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setExtractedReading(m3("100.4"));
        event.setConfirmedReading(m3("95.7"));
        event.setConfidence(90);
        event.setImageUrl("img");
        event.setReadingAt("2026-01-01T10:15:00");
        event.setChannel(1);
        event.setReadingDate("2026-01-01");
        event.setSubmissionStatus(1);
        event.setReadingType(0);
        when(dimDateRepository.findByFullDate(any())).thenReturn(Optional.empty());
        when(factOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any()))
                .thenReturn(false);

        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository, times(1)).upsertMeterReading(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(1);
        assertThat(captor.getValue().getSchemeId()).isEqualTo(11);
        // The meters' decimal digit reaches the column intact — no rounding anywhere on this path.
        assertThat(captor.getValue().getExtractedReading()).isEqualByComparingTo("100.4");
        assertThat(captor.getValue().getConfirmedReading()).isEqualByComparingTo("95.7");
        assertThat(captor.getValue().getReadingAt()).isEqualTo(LocalDateTime.parse("2026-01-01T10:15:00"));
        assertThat(captor.getValue().getReadingDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(captor.getValue().getSubmissionStatus()).isEqualTo(1);
        assertThat(captor.getValue().getReadingType()).isEqualTo(0);
    }

    @Test
    void ingestMeterReading_carriesTheSubmissionIdentitySnapshotAndUnitIntoTheFactRow() {
        MeterReadingEvent event = readingEvent("40", "2026-01-02");
        event.setSourceReadingId(501L);
        event.setSourceUpdatedAt("2026-01-02T10:15:30.123456");
        CalculationParameters snapshot = new CalculationParameters(1, "F2", new BigDecimal("0.95"), List.of());
        event.setCalculationParameters(snapshot);
        event.setSubmittedUnit("kV.A.h");
        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository).upsertMeterReading(captor.capture());
        assertThat(captor.getValue().getSourceReadingId()).isEqualTo(501L);
        assertThat(captor.getValue().getSourceUpdatedAt())
                .isEqualTo(LocalDateTime.parse("2026-01-02T10:15:30.123456"));
        assertThat(captor.getValue().getCalculationParameters()).isEqualTo(snapshot);
        assertThat(captor.getValue().getSubmittedUnit()).isEqualTo("kV.A.h");
    }

    @Test
    void ingestMeterReading_fromAnOlderTelemetryIsStoredWithoutASubmissionIdentity() {
        // No sourceReadingId: the upsert inserts a new row, exactly as every event did before V56.
        MeterReadingEvent event = readingEvent("40", "2026-01-02");
        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository).upsertMeterReading(captor.capture());
        assertThat(captor.getValue().getSourceReadingId()).isNull();
        assertThat(captor.getValue().getSourceUpdatedAt()).isNull();
        assertThat(captor.getValue().getCalculationParameters()).isNull();
        assertThat(captor.getValue().getSubmittedUnit()).isNull();
        verify(waterQuantityRecalculationService)
                .recalculateAfterReading(1, 11, LocalDate.of(2026, 1, 2));
    }

    @Test
    void ingestMeterReading_anUnparseableVersionFailsTheEventBeforeAnyWrite() {
        // Falling back to now would let a broken event outrank every real version of the submission,
        // and treating it as unknown would drop it as stale against any stored one.
        MeterReadingEvent event = readingEvent("40", "2026-01-02");
        event.setSourceReadingId(501L);
        event.setSourceUpdatedAt("not-a-timestamp");

        assertThrows(MalformedEventException.class, () -> service.ingestMeterReading(event));

        assertThat(meterRegistry.counter("meter_reading.source_updated_at.unparseable").count()).isEqualTo(1.0);
        verifyNoInteractions(factIngestionRepository, waterQuantityRecalculationService,
                factOperatorAttendanceRepository);
    }

    @Test
    void ingestMeterReading_locksTheSchemeBeforeWritingTheReading() {
        MeterReadingEvent event = readingEvent("40", "2026-01-02");
        storedAsNewRow();

        service.ingestMeterReading(event);

        InOrder order = inOrder(factIngestionRepository, waterQuantityRecalculationService);
        order.verify(factIngestionRepository).lockScheme(1, 11);
        order.verify(factIngestionRepository).upsertMeterReading(any());
        order.verify(waterQuantityRecalculationService).recalculateAfterReading(any(), any(), any());
    }

    @Test
    void ingestMeterReading_aStaleEventIsCountedAndChangesNothingElse() {
        // The upsert refused it: a newer version of the submission is already stored.
        MeterReadingEvent event = readingEvent("40", "2026-01-02");
        event.setSourceReadingId(501L);
        event.setSourceUpdatedAt("2026-01-02T08:00:00");
        when(factIngestionRepository.upsertMeterReading(any())).thenReturn(Optional.empty());

        service.ingestMeterReading(event);

        assertThat(meterRegistry.counter("meter_reading.stale_event").count()).isEqualTo(1.0);
        verify(waterQuantityRecalculationService, never()).recalculateAfterReading(any(), any(), any());
        verify(factOperatorAttendanceRepository, never()).save(any());
        verify(dimDateRepository, never()).findByFullDate(any());
    }

    // ---- a correction that moves the reading to another scheme or day ------------------------

    @Test
    void ingestMeterReading_aReadingMovedToAnotherSchemeLocksBothAndRecalculatesTheDayItLeft() {
        MeterReadingEvent event = versionedReadingEvent("2026-01-02");
        when(factIngestionRepository.findSchemeDay(1, 501L))
                .thenReturn(Optional.of(new SchemeDay(12, LocalDate.of(2026, 1, 1))));
        storedAsNewRow();

        service.ingestMeterReading(event);

        InOrder order = inOrder(factIngestionRepository, waterQuantityRecalculationService);
        order.verify(factIngestionRepository).findSchemeDay(1, 501L);
        order.verify(factIngestionRepository).lockSchemes(1, Set.of(11, 12));
        // Read again under the locks: another event may have moved it in between.
        order.verify(factIngestionRepository).findSchemeDay(1, 501L);
        order.verify(factIngestionRepository).upsertMeterReading(any());
        order.verify(waterQuantityRecalculationService).recalculateAfterReading(1, 11, LocalDate.of(2026, 1, 2));
        order.verify(waterQuantityRecalculationService).recalculateAfterRemoval(1, 12, LocalDate.of(2026, 1, 1));
    }

    @Test
    void ingestMeterReading_aReadingMovedToAnotherDayRecalculatesTheDayItLeft() {
        MeterReadingEvent event = versionedReadingEvent("2026-01-02");
        when(factIngestionRepository.findSchemeDay(1, 501L))
                .thenReturn(Optional.of(new SchemeDay(11, LocalDate.of(2026, 1, 1))));
        storedAsNewRow();

        service.ingestMeterReading(event);

        verify(factIngestionRepository).lockSchemes(1, Set.of(11));
        verify(waterQuantityRecalculationService).recalculateAfterReading(1, 11, LocalDate.of(2026, 1, 2));
        verify(waterQuantityRecalculationService).recalculateAfterRemoval(1, 11, LocalDate.of(2026, 1, 1));
    }

    @Test
    void ingestMeterReading_aCorrectionThatStaysOnItsDayRecalculatesOnlyThatDay() {
        MeterReadingEvent event = versionedReadingEvent("2026-01-02");
        when(factIngestionRepository.findSchemeDay(1, 501L))
                .thenReturn(Optional.of(new SchemeDay(11, LocalDate.of(2026, 1, 2))));
        storedAsNewRow();

        service.ingestMeterReading(event);

        verify(waterQuantityRecalculationService).recalculateAfterReading(1, 11, LocalDate.of(2026, 1, 2));
        verify(waterQuantityRecalculationService, never()).recalculateAfterRemoval(any(), any(), any());
    }

    @Test
    void ingestMeterReading_aReadingMovedToASchemeNotLockedMeanwhileIsRetried() {
        // Stored under scheme 11 when first looked up, and moved to 13 by another event before the
        // locks were granted: writing now would change scheme 13's readings without its lock.
        MeterReadingEvent event = versionedReadingEvent("2026-01-02");
        when(factIngestionRepository.findSchemeDay(1, 501L)).thenReturn(
                Optional.of(new SchemeDay(11, LocalDate.of(2026, 1, 2))),
                Optional.of(new SchemeDay(13, LocalDate.of(2026, 1, 2))));

        assertThrows(ConcurrencyFailureException.class, () -> service.ingestMeterReading(event));

        verify(factIngestionRepository, never()).upsertMeterReading(any());
        verify(waterQuantityRecalculationService, never()).recalculateAfterReading(any(), any(), any());
    }

    @Test
    void ingestMeterReading_aStaleEventLeavesTheDayItsSubmissionIsStoredOnAlone() {
        MeterReadingEvent event = versionedReadingEvent("2026-01-02");
        when(factIngestionRepository.findSchemeDay(1, 501L))
                .thenReturn(Optional.of(new SchemeDay(12, LocalDate.of(2026, 1, 1))));
        when(factIngestionRepository.upsertMeterReading(any())).thenReturn(Optional.empty());

        service.ingestMeterReading(event);

        verify(waterQuantityRecalculationService, never()).recalculateAfterRemoval(any(), any(), any());
    }

    @Test
    void ingestMeterReading_aLegacyEventLocksOnlyItsSchemeAndNeverLooksUpAStoredRow() {
        MeterReadingEvent event = readingEvent("40", "2026-01-02");
        storedAsNewRow();

        service.ingestMeterReading(event);

        verify(factIngestionRepository).lockScheme(1, 11);
        verify(factIngestionRepository, never()).findSchemeDay(any(), any());
        verify(waterQuantityRecalculationService, never()).recalculateAfterRemoval(any(), any(), any());
    }

    @Test
    void ingestWaterQuantity_locksTheSchemeBeforeItsFirstWrite() {
        // Both writers of a day's row find it and then update or insert; the reading path holds the
        // same lock, so the two cannot interleave. Taken before the dim_tenant/dim_date inserts too,
        // so a reading event holding the lock can never be waiting on one of this event's writes.
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setWaterQuantity(BigDecimal.ZERO);
        event.setSubmissionStatus(0);
        event.setOutageReason("power cut");
        event.setDate("2026-01-02");

        service.ingestWaterQuantity(event);

        InOrder order = inOrder(factIngestionRepository, dimTenantRepository, dimDateRepository, waterQuantityRepository);
        order.verify(factIngestionRepository).lockScheme(1, 11);
        order.verify(dimTenantRepository).existsById(1);
        order.verify(dimDateRepository).findByFullDate(LocalDate.of(2026, 1, 2));
        order.verify(waterQuantityRepository)
                .findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(1, 11, LocalDate.of(2026, 1, 2));
        order.verify(waterQuantityRepository).save(any());
    }

    @Test
    void ingestWaterQuantity_whenTheReportedVolumeCannotBeStored_recordsNothingForTheDay() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setWaterQuantity(m3("1e16"));
        event.setSubmissionStatus(1);
        event.setDate("2026-01-02");

        service.ingestWaterQuantity(event);

        verify(waterQuantityRepository, never()).save(any());
        assertThat(meterRegistry.counter("water_quantity.unstorable", "source", "correction").count())
                .isEqualTo(1.0);
    }

    /** A reading or derived volume in the meter's native cubic metres. */
    private static BigDecimal m3(String cubicMetres) {
        return cubicMetres == null ? null : new BigDecimal(cubicMetres);
    }

    /** The upsert stored the event's row, so ingestion carries on past it. */
    private void storedAsNewRow() {
        when(factIngestionRepository.upsertMeterReading(any())).thenReturn(Optional.of(1L));
    }

    private static MeterReadingEvent readingEvent(String confirmedReading, String readingDate) {
        MeterReadingEvent event = new MeterReadingEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setConfirmedReading(m3(confirmedReading));
        event.setReadingAt(readingDate + "T10:15:00");
        event.setReadingDate(readingDate);
        event.setSubmissionStatus(1);
        event.setReadingType(0);
        return event;
    }

    /** A reading from a telemetry-service that sends the submission's identity and version. */
    private static MeterReadingEvent versionedReadingEvent(String readingDate) {
        MeterReadingEvent event = readingEvent("40", readingDate);
        event.setSourceReadingId(501L);
        event.setSourceUpdatedAt(readingDate + "T10:15:30.123456");
        return event;
    }

    @Test
    void ingestWaterQuantity_whenInvalidDate_fallsBackToToday() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(21);
        event.setWaterQuantity(m3("120"));
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
        event.setWaterQuantity(m3("200"));
        event.setSubmissionStatus(1);
        event.setDate("2026-01-05");

        FactWaterQuantity existing = FactWaterQuantity.builder()
                .id(99L)
                .tenantId(1)
                .schemeId(11)
                .userId(10)
                .waterQuantity(100_000L)
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
        // The correction event carries the meter's native 200 m3; the column is litres.
        assertThat(captor.getValue().getWaterQuantity()).isEqualTo(200_000L);
        assertThat(captor.getValue().getSubmissionStatus()).isEqualTo(1);
    }

    @Test
    void ingestWaterQuantity_convertsTheEventsCubicMetresToLitres() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(22);
        event.setWaterQuantity(m3("37"));
        event.setSubmissionStatus(1);
        event.setDate("2026-01-05");

        when(dimDateRepository.findByFullDate(LocalDate.of(2026, 1, 5))).thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                1, 11, LocalDate.of(2026, 1, 5)))
                .thenReturn(Optional.empty());

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository).save(captor.capture());
        assertThat(captor.getValue().getWaterQuantity()).isEqualTo(37_000L);
    }

    @Test
    void ingestWaterQuantity_whenIncomingWaterQuantityIsNegative_storesZero() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(22);
        event.setWaterQuantity(m3("-25"));
        event.setSubmissionStatus(1);
        event.setDate("2026-01-05");

        when(dimDateRepository.findByFullDate(LocalDate.of(2026, 1, 5))).thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                1, 11, LocalDate.of(2026, 1, 5)))
                .thenReturn(Optional.empty());

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository).save(captor.capture());
        assertThat(captor.getValue().getWaterQuantity()).isZero();
    }

    @Test
    void ingestWaterQuantity_implausibleQuantityIsCountedButStoredUnclamped() {
        WaterQuantityEvent event = new WaterQuantityEvent();
        event.setTenantId(1);
        event.setSchemeId(11);
        event.setUserId(22);
        // A whole cumulative meter index mistaken for a day's supply — far past the 100,000 m3 threshold.
        event.setWaterQuantity(m3("5000000"));
        event.setSubmissionStatus(1);
        event.setDate("2026-01-05");

        when(dimDateRepository.findByFullDate(LocalDate.of(2026, 1, 5))).thenReturn(Optional.empty());
        when(waterQuantityRepository.findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
                1, 11, LocalDate.of(2026, 1, 5)))
                .thenReturn(Optional.empty());

        service.ingestWaterQuantity(event);

        ArgumentCaptor<FactWaterQuantity> captor = ArgumentCaptor.forClass(FactWaterQuantity.class);
        verify(waterQuantityRepository).save(captor.capture());
        // Reported, never clamped: clamping would invent data and hide the bad reading.
        assertThat(captor.getValue().getWaterQuantity()).isEqualTo(5_000_000_000L);
        assertThat(meterRegistry.counter("water_quantity.implausible", "source", "correction").count())
                .isEqualTo(1.0);
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
        when(factOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any())).thenReturn(false);

        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository, times(1)).upsertMeterReading(captor.capture());
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
        when(factOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any())).thenReturn(false);

        storedAsNewRow();

        service.ingestMeterReading(event);

        ArgumentCaptor<FactMeterReading> captor = ArgumentCaptor.forClass(FactMeterReading.class);
        verify(factIngestionRepository, times(1)).upsertMeterReading(captor.capture());
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
        when(factOperatorAttendanceRepository.existsByTenantIdAndSchemeIdAndUserIdAndDateKey(any(), any(), any(), any())).thenReturn(true);

        storedAsNewRow();

        service.ingestMeterReading(event);

        verify(factOperatorAttendanceRepository, never()).save(any());
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
