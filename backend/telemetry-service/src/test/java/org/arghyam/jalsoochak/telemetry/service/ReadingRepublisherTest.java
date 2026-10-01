package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.telemetry.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    /** The event handed to the executor-backed publisher. */
    private MeterReadingEvent published() {
        ArgumentCaptor<MeterReadingEvent> event = ArgumentCaptor.forClass(MeterReadingEvent.class);
        verify(telemetryEventPublisher).publishMeterReadingRecorded(event.capture());
        return event.getValue();
    }

    private void storedRow(TelemetryLatestFlowReadingRecord row) {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID)).thenReturn(Optional.of(row));
    }

    /**
     * The row's id and updated_at identify the submission and its version, so analytics updates
     * the one fact row it already holds for this reading instead of adding a second. The snapshot is
     * taken now, for the row's own channel, so a correction is calculated with current pump data.
     */
    @Test
    void publishesTheStoredRow() {
        storedRow(row(new BigDecimal("100"), READING_DATE, "ELM"));
        when(calculationParametersSnapshotter.snapshot(SCHEMA, TENANT_ID, 10L, ReadingChannel.ELM))
                .thenReturn(SNAPSHOT);

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        MeterReadingEvent event = published();
        assertEquals(TENANT_ID, event.getTenantId());
        assertEquals(10, event.getSchemeId());
        assertEquals(1, event.getUserId());
        assertEquals(new BigDecimal("100"), event.getExtractedReading());
        assertEquals(new BigDecimal("123"), event.getConfirmedReading());
        assertNull(event.getConfidence());
        assertEquals("http://example.com/img.jpg", event.getImageUrl());
        assertEquals(READING_AT.toString(), event.getReadingAt());
        assertEquals(ReadingChannel.ELM.getCode(), event.getChannel());
        assertEquals(READING_DATE.toString(), event.getReadingDate());
        assertEquals(1, event.getSubmissionStatus());
        assertEquals(0, event.getReadingType());
        assertEquals("corr-1", event.getCorrelationId());
        assertEquals(READING_ID, event.getSourceReadingId());
        assertEquals("2026-06-23T08:15:02.345678", event.getSourceUpdatedAt());
        assertSame(SNAPSHOT, event.getCalculationParameters());
    }

    /**
     * extracted_reading = 0 is the "nothing extracted this" sentinel that every non-OCR row carries —
     * an API submission that supplied confirmed_reading, or a hand-typed reading that opened the row.
     * Republishing it as 0 would file the row under "operator overrode the AI" on the dashboards,
     * which needs an AI reading to have existed.
     */
    @Test
    void publishesNoExtractedReadingForTheZeroSentinel() {
        storedRow(row(BigDecimal.ZERO, READING_DATE, "BFM"));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        MeterReadingEvent event = published();
        assertNull(event.getExtractedReading());
        assertEquals(new BigDecimal("123"), event.getConfirmedReading());
    }

    /** Analytics reads a missing channel as BFM, so a legacy row must not be given one. */
    @Test
    void publishesNoChannelForALegacyRowWithoutOne() {
        storedRow(row(BigDecimal.ZERO, READING_DATE, null));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verify(calculationParametersSnapshotter).snapshot(SCHEMA, TENANT_ID, 10L, ReadingChannel.BFM);
        assertNull(published().getChannel());
    }

    @Test
    void takesTheReadingDateFromReadingAtWhenTheRowHasNone() {
        storedRow(row(BigDecimal.ZERO, null, "BFM"));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        MeterReadingEvent event = published();
        assertEquals(READING_AT.toString(), event.getReadingAt());
        assertEquals(READING_AT.toLocalDate().toString(), event.getReadingDate());
    }

    /**
     * SUPPLY-PLAUSIBILITY: telemetry keeps a quarantined row out of every baseline, so analytics must
     * not count it either. A WhatsApp manual reading can overwrite such a row without releasing it.
     */
    @Test
    void withholdsARowThatIsStillQuarantined() {
        storedRow(row(BigDecimal.ZERO, READING_DATE, "BFM", QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verifyNoInteractions(telemetryEventPublisher, calculationParametersSnapshotter);
    }

    /** A pre-V40 schema has no quarantine column, so its rows read back with no marker at all. */
    @Test
    void publishesARowFromASchemaWithoutQuarantine() {
        storedRow(row(BigDecimal.ZERO, READING_DATE, "BFM", null));

        readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(any(MeterReadingEvent.class));
    }

    @Test
    void failsWithoutPublishingWhenTheRowIsGone() {
        when(telemetryTenantRepository.findFlowReadingById(SCHEMA, READING_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> readingRepublisher.republish(SCHEMA, TENANT_ID, READING_ID));

        verifyNoInteractions(telemetryEventPublisher);
    }

    @Nested
    class AwaitingTheAcknowledgement {

        /** The same event as the queued path, published on the calling thread instead. */
        @Test
        void reportsAReadingKafkaAcknowledged() {
            storedRow(row(new BigDecimal("100"), READING_DATE, "PDU"));
            when(calculationParametersSnapshotter.snapshot(SCHEMA, TENANT_ID, 10L, ReadingChannel.PDU))
                    .thenReturn(SNAPSHOT);
            ArgumentCaptor<MeterReadingEvent> event = ArgumentCaptor.forClass(MeterReadingEvent.class);
            when(telemetryEventPublisher.publishMeterReadingRecordedAndAwait(event.capture())).thenReturn(true);

            assertEquals(ReadingRepublisher.Result.PUBLISHED,
                    readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, READING_ID));

            assertEquals(READING_ID, event.getValue().getSourceReadingId());
            assertEquals(ReadingChannel.PDU.getCode(), event.getValue().getChannel());
            assertSame(SNAPSHOT, event.getValue().getCalculationParameters());
            verify(telemetryEventPublisher, never()).publishMeterReadingRecorded(any(MeterReadingEvent.class));
        }

        @Test
        void reportsAReadingKafkaDidNotAcknowledge() {
            storedRow(row(BigDecimal.ZERO, READING_DATE, "PDU"));
            when(telemetryEventPublisher.publishMeterReadingRecordedAndAwait(any())).thenReturn(false);

            assertEquals(ReadingRepublisher.Result.NOT_ACKNOWLEDGED,
                    readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, READING_ID));
        }

        @Test
        void withholdsARowThatIsStillQuarantined() {
            storedRow(row(BigDecimal.ZERO, READING_DATE, "PDU", QuarantineReason.IMPLAUSIBLE_WATER_SUPPLY));

            assertEquals(ReadingRepublisher.Result.WITHHELD,
                    readingRepublisher.republishAndAwait(SCHEMA, TENANT_ID, READING_ID));

            verifyNoInteractions(telemetryEventPublisher, calculationParametersSnapshotter);
        }
    }
}
