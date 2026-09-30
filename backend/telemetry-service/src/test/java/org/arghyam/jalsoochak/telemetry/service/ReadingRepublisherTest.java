package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingRepublisherTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 22;
    private static final long READING_ID = 99L;
    private static final LocalDate READING_DATE = LocalDate.of(2026, 6, 22);
    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 6, 22, 9, 30);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 23, 8, 15, 2, 345_678_000);
    private static final CalculationParameters SNAPSHOT =
            new CalculationParameters(CalculationParameters.VERSION, "F1", null, List.of());

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;

    @Mock
    private CalculationParametersSnapshotter calculationParametersSnapshotter;

    @InjectMocks
    private ReadingRepublisher readingRepublisher;

    private static TelemetryLatestFlowReadingRecord row(BigDecimal extracted,
                                                        LocalDate readingDate,
                                                        String channel) {
        return row(extracted, readingDate, channel, QuarantineReason.NONE);
    }

    private static TelemetryLatestFlowReadingRecord row(BigDecimal extracted,
                                                        LocalDate readingDate,
                                                        String channel,
                                                        Integer quarantineReason) {
        return new TelemetryLatestFlowReadingRecord(
                READING_ID, 10L, 1L, "corr-1",
                extracted, new BigDecimal("123"), "http://example.com/img.jpg",
                readingDate, READING_AT, channel, quarantineReason, UPDATED_AT);
    }

    /**
     * The row's id and updated_at identify the submission and its version, so analytics updates
     * the one fact row it already holds for this reading instead of adding a second. The snapshot is
     * taken now, for the row's own channel, so a correction is calculated with current pump data.
     */
    @Test
    void publishesTheStoredRow() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID))
                .thenReturn(Optional.of(row(new BigDecimal("100"), READING_DATE, "ELM")));
        when(calculationParametersSnapshotter.snapshot(SCHEMA, TENANT_ID, 10L, ReadingChannel.ELM))
                .thenReturn(SNAPSHOT);

        assertTrue(readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID));

        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                TENANT_ID,
                10L,
                1L,
                new BigDecimal("100"),
                new BigDecimal("123"),
                null,
                "http://example.com/img.jpg",
                READING_AT,
                ReadingChannel.ELM.getCode(),
                READING_DATE,
                1,
                0,
                "corr-1",
                READING_ID,
                UPDATED_AT,
                SNAPSHOT);
    }

    /**
     * extracted_reading = 0 is the "nothing extracted this" sentinel that every non-OCR row carries —
     * an API submission that supplied confirmed_reading, or a hand-typed reading that opened the row.
     * Republishing it as 0 would file the row under "operator overrode the AI" on the dashboards,
     * which needs an AI reading to have existed.
     */
    @Test
    void publishesNoExtractedReadingForTheZeroSentinel() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID))
                .thenReturn(Optional.of(row(BigDecimal.ZERO, READING_DATE, "BFM")));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                eq(TENANT_ID), eq(10L), eq(1L), isNull(), eq(new BigDecimal("123")), isNull(), any(),
                any(), any(), any(), eq(1), eq(0), any(), any(), any(), any());
    }

    /** Analytics reads a missing channel as BFM, so a legacy row must not be given one. */
    @Test
    void publishesNoChannelForALegacyRowWithoutOne() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID))
                .thenReturn(Optional.of(row(BigDecimal.ZERO, READING_DATE, null)));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verify(calculationParametersSnapshotter).snapshot(SCHEMA, TENANT_ID, 10L, ReadingChannel.BFM);
        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                any(), any(), any(), any(), any(), any(), any(), any(), isNull(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void takesTheReadingDateFromReadingAtWhenTheRowHasNone() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID))
                .thenReturn(Optional.of(row(BigDecimal.ZERO, null, "BFM")));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                any(), any(), any(), any(), any(), any(), any(), eq(READING_AT), any(),
                eq(READING_AT.toLocalDate()), any(), any(), any(), any(), any(), any());
    }

    /**
     * SUPPLY-PLAUSIBILITY: telemetry keeps a quarantined row out of every baseline, so analytics must
     * not count it either. A WhatsApp manual reading can overwrite such a row without releasing it.
     */
    @Test
    void withholdsARowThatIsStillQuarantined() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID))
                .thenReturn(Optional.of(row(BigDecimal.ZERO, READING_DATE, "BFM",
                        QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY)));

        assertFalse(readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID));

        verifyNoInteractions(telemetryEventPublisher, calculationParametersSnapshotter);
    }

    /** A pre-V40 schema has no quarantine column, so its rows read back with no marker at all. */
    @Test
    void publishesARowFromASchemaWithoutQuarantine() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID))
                .thenReturn(Optional.of(row(BigDecimal.ZERO, READING_DATE, "BFM", null)));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failsWithoutPublishingWhenTheRowIsGone() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID));

        verifyNoInteractions(telemetryEventPublisher);
    }
}
