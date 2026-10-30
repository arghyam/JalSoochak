package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.MeterRegister;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.ReadingRepublisher.Result;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityGuard;
import org.arghyam.jalsoochak.telemetry.service.water.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuarantinedReadingReleaseServiceTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 22;
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final TelemetryConfirmedReadingSnapshot BASELINE = new TelemetryConfirmedReadingSnapshot(
            new BigDecimal("900"), LocalDateTime.of(2026, 9, 4, 9, 0), LocalDateTime.of(2026, 9, 4, 9, 0));
    private static final Verdict ACCEPTED = new Verdict.Accepted(
            new BigDecimal("50000"), new BigDecimal("75000"), new BigDecimal("500"));
    private static final Verdict QUARANTINED = new Verdict.Quarantined(
            new BigDecimal("200000"), new BigDecimal("75000"), new BigDecimal("500"));

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @Mock
    private SupplyPlausibilityGuard supplyPlausibilityGuard;

    @Mock
    private ReadingRepublisher readingRepublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private QuarantinedReadingReleaseService releaseService;

    private static TelemetryLatestFlowReadingRecord quarantined(long id, LocalDate readingDate, String confirmed) {
        return new TelemetryLatestFlowReadingRecord(
                id, SCHEME_ID, OPERATOR_ID, "corr-" + id, BigDecimal.ZERO, new BigDecimal(confirmed), "",
                readingDate, readingDate.atTime(9, 30), ReadingChannel.BFM.getCode(), "m3",
                QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY, null);
    }

    private void tenantSchema() {
        when(telemetryTenantRepository.findSchemaNameByTenantId(TENANT_ID)).thenReturn(Optional.of(SCHEMA));
    }

    private void quarantinedReadings(TelemetryLatestFlowReadingRecord... readings) {
        when(telemetryTenantRepository.findQuarantinedReadings(SCHEMA, FROM, TO, null)).thenReturn(List.of(readings));
    }

    /** Every reading is measured against {@link #BASELINE} and the check answers {@code verdict}. */
    private void assessedAs(TelemetryLatestFlowReadingRecord reading, Verdict verdict) {
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshotBeforeDate(
                SCHEMA, SCHEME_ID, ReadingChannel.BFM, MeterRegister.STANDARD, reading.readingDate(), reading.id()))
                .thenReturn(Optional.of(BASELINE));
        when(supplyPlausibilityGuard.assess(SCHEMA, TENANT_ID, OPERATOR_ID, SCHEME_ID, reading.readingDate(),
                reading.confirmedReading(), BASELINE, SupplyPlausibilityGuard.Path.RELEASE))
                .thenReturn(verdict);
    }

    private QuarantinedReadingReleaseService.Outcome release(boolean dryRun) {
        return releaseService.release(TENANT_ID, FROM, TO, null, null, dryRun);
    }

    @Test
    void findsTheTenantByItsStateCode() {
        when(tenantConfigRepository.findTenantIdByStateCode("as")).thenReturn(Optional.of(TENANT_ID));

        assertEquals(Optional.of(TENANT_ID), releaseService.findTenantId("as"));
    }

    /** The release path of a passing correction: the marker is cleared before the row is read back. */
    @Test
    void releasesAReadingTheCheckNowAcceptsAndPublishesIt() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord reading = quarantined(5L, LocalDate.of(2026, 9, 7), "1100");
        quarantinedReadings(reading);
        assessedAs(reading, ACCEPTED);
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 5L)).thenReturn(Result.PUBLISHED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(1, 0, 0, 0), release(false));

        InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
        order.verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 5L, QuarantineReason.NONE);
        order.verify(readingRepublisher).republishAndAwait(SCHEMA, TENANT_ID, 5L);
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void keepsAReadingTheCheckStillFindsImplausible() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord reading = quarantined(5L, LocalDate.of(2026, 9, 7), "1100");
        quarantinedReadings(reading);
        assessedAs(reading, QUARANTINED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(0, 1, 0, 0), release(false));

        verify(telemetryTenantRepository, never()).applyQuarantineReason(anyString(), anyLong(), anyInt());
        verifyNoInteractions(readingRepublisher);
    }

    /** As on submission: a reading the check cannot assess is served, not held back. */
    @Test
    void releasesAReadingTheCheckCannotAssess() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord reading = quarantined(5L, LocalDate.of(2026, 9, 7), "1100");
        quarantinedReadings(reading);
        assessedAs(reading, new Verdict.Skipped(Verdict.SkipReason.NO_POPULATION));
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 5L)).thenReturn(Result.PUBLISHED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(1, 0, 0, 0), release(false));

        verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 5L, QuarantineReason.NONE);
    }

    /** A released reading is the next one's baseline, so it is released before the next is assessed. */
    @Test
    void releasesEachReadingBeforeAssessingTheNext() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord first = quarantined(5L, LocalDate.of(2026, 9, 7), "1000");
        TelemetryLatestFlowReadingRecord second = quarantined(6L, LocalDate.of(2026, 9, 8), "1050");
        quarantinedReadings(first, second);
        assessedAs(first, ACCEPTED);
        assessedAs(second, ACCEPTED);
        when(readingRepublisher.republishAndAwait(eq(SCHEMA), eq(TENANT_ID), anyLong())).thenReturn(Result.PUBLISHED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(2, 0, 0, 0), release(false));

        InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
        order.verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 5L, QuarantineReason.NONE);
        order.verify(readingRepublisher).republishAndAwait(SCHEMA, TENANT_ID, 5L);
        order.verify(telemetryTenantRepository).findLatestConfirmedReadingSnapshotBeforeDate(
                SCHEMA, SCHEME_ID, ReadingChannel.BFM, MeterRegister.STANDARD, second.readingDate(), 6L);
        order.verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 6L, QuarantineReason.NONE);
    }

    /**
     * The event may or may not arrive, and an unmarked reading is never picked up again, so the reading
     * goes back into quarantine for a rerun to release.
     */
    @Test
    void quarantinesAgainAndStopsWhenKafkaDoesNotAcknowledge() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord first = quarantined(5L, LocalDate.of(2026, 9, 7), "1000");
        TelemetryLatestFlowReadingRecord second = quarantined(6L, LocalDate.of(2026, 9, 8), "1050");
        TelemetryLatestFlowReadingRecord third = quarantined(7L, LocalDate.of(2026, 9, 9), "1100");
        quarantinedReadings(first, second, third);
        assessedAs(first, ACCEPTED);
        assessedAs(second, ACCEPTED);
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 5L)).thenReturn(Result.PUBLISHED);
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 6L)).thenReturn(Result.NOT_ACKNOWLEDGED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(1, 0, 0, 2), release(false));

        InOrder order = inOrder(telemetryTenantRepository);
        order.verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 6L, QuarantineReason.NONE);
        order.verify(telemetryTenantRepository)
                .applyQuarantineReason(SCHEMA, 6L, QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY);
        verify(supplyPlausibilityGuard, never()).assess(any(), any(), any(), any(), eq(third.readingDate()),
                any(), any(), any());
        verify(readingRepublisher, never()).republishAndAwait(SCHEMA, TENANT_ID, 7L);
    }

    /** As when Kafka does not acknowledge: the marker is already committed, so an unsent reading goes back. */
    @Test
    void quarantinesAgainAndRethrowsWhenThePublishFails() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord reading = quarantined(5L, LocalDate.of(2026, 9, 7), "1100");
        quarantinedReadings(reading);
        assessedAs(reading, ACCEPTED);
        IllegalStateException failure = new IllegalStateException("Flow reading 5 not found in " + SCHEMA);
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 5L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> release(false)));

        InOrder order = inOrder(telemetryTenantRepository);
        order.verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 5L, QuarantineReason.NONE);
        order.verify(telemetryTenantRepository)
                .applyQuarantineReason(SCHEMA, 5L, QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY);
    }

    @Test
    void keepsThePublishFailureWhenQuarantiningAgainFailsToo() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord reading = quarantined(5L, LocalDate.of(2026, 9, 7), "1100");
        quarantinedReadings(reading);
        assessedAs(reading, ACCEPTED);
        IllegalStateException failure = new IllegalStateException("publish failed");
        IllegalStateException restoreFailure = new IllegalStateException("restore failed");
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 5L)).thenThrow(failure);
        // The clear succeeds; putting the marker back fails.
        doNothing().doThrow(restoreFailure).when(telemetryTenantRepository)
                .applyQuarantineReason(eq(SCHEMA), eq(5L), anyInt());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> release(false));

        assertSame(failure, thrown);
        assertArrayEquals(new Throwable[]{restoreFailure}, thrown.getSuppressed());
        verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 5L, QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY);
    }

    /** Releasing it would publish a reading with no value, so it is left quarantined and counted apart. */
    @Test
    void keepsAReadingWithNoConfirmedValueAndCarriesOn() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord noValue = new TelemetryLatestFlowReadingRecord(
                5L, SCHEME_ID, OPERATOR_ID, "corr-5", BigDecimal.ZERO, null, "",
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7).atTime(9, 30), ReadingChannel.BFM.getCode(), "m3",
                QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY, null);
        TelemetryLatestFlowReadingRecord next = quarantined(6L, LocalDate.of(2026, 9, 8), "1050");
        quarantinedReadings(noValue, next);
        assessedAs(next, ACCEPTED);
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 6L)).thenReturn(Result.PUBLISHED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(1, 0, 1, 0), release(false));

        verify(supplyPlausibilityGuard, never()).assess(any(), any(), any(), any(), eq(noValue.readingDate()),
                any(), any(), any());
        verify(telemetryTenantRepository, never()).applyQuarantineReason(eq(SCHEMA), eq(5L), anyInt());
        verify(readingRepublisher, never()).republishAndAwait(SCHEMA, TENANT_ID, 5L);
    }

    @Test
    void countsAReadingQuarantinedAgainBeforeItWasReadBackAsStillQuarantined() {
        tenantSchema();
        TelemetryLatestFlowReadingRecord reading = quarantined(5L, LocalDate.of(2026, 9, 7), "1100");
        quarantinedReadings(reading);
        assessedAs(reading, ACCEPTED);
        when(readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, 5L)).thenReturn(Result.WITHHELD);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(0, 1, 0, 0), release(false));
    }

    /** The same pass, so the counts are the release's own, inside a transaction that is rolled back. */
    @Test
    void dryRunAssessesAndCountsInsideARolledBackTransactionAndPublishesNothing() {
        tenantSchema();
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionTemplate.execute(any())).thenAnswer(
                invocation -> invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(status));
        TelemetryLatestFlowReadingRecord accepted = quarantined(5L, LocalDate.of(2026, 9, 7), "1000");
        TelemetryLatestFlowReadingRecord implausible = quarantined(6L, LocalDate.of(2026, 9, 8), "9000");
        quarantinedReadings(accepted, implausible);
        assessedAs(accepted, ACCEPTED);
        assessedAs(implausible, QUARANTINED);

        assertEquals(new QuarantinedReadingReleaseService.Outcome(1, 1, 0, 0), release(true));

        verify(status).setRollbackOnly();
        verify(telemetryTenantRepository).applyQuarantineReason(SCHEMA, 5L, QuarantineReason.NONE);
        verifyNoInteractions(readingRepublisher);
    }

    @Test
    void looksTheStateSchemeIdUpFirst() {
        tenantSchema();
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1")).thenReturn(Optional.of(SCHEME_ID));
        when(telemetryTenantRepository.findQuarantinedReadings(SCHEMA, FROM, TO, SCHEME_ID)).thenReturn(List.of());

        releaseService.release(TENANT_ID, FROM, TO, "S-1", "C-1", false);

        verify(telemetryTenantRepository, never()).findSchemeIdByCentreSchemeId(any(), any());
        verify(telemetryTenantRepository).findQuarantinedReadings(SCHEMA, FROM, TO, SCHEME_ID);
    }

    @Test
    void fallsBackToTheCentreSchemeId() {
        tenantSchema();
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1")).thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(SCHEMA, "C-1")).thenReturn(Optional.of(8L));
        when(telemetryTenantRepository.findQuarantinedReadings(SCHEMA, FROM, TO, 8L)).thenReturn(List.of());

        releaseService.release(TENANT_ID, FROM, TO, "S-1", "C-1", false);

        verify(telemetryTenantRepository).findQuarantinedReadings(SCHEMA, FROM, TO, 8L);
    }

    @Test
    void refusesASchemeIdThatMatchesNoScheme() {
        tenantSchema();
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-404")).thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(SCHEMA, null)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> releaseService.release(TENANT_ID, FROM, TO, "S-404", null, false));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(telemetryTenantRepository, never()).findQuarantinedReadings(any(), any(), any(), any());
        verifyNoInteractions(supplyPlausibilityGuard, readingRepublisher);
    }

    @Test
    void failsWhenTheTenantHasNoSchema() {
        when(telemetryTenantRepository.findSchemaNameByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> release(false));

        verifyNoInteractions(supplyPlausibilityGuard, readingRepublisher);
    }
}
