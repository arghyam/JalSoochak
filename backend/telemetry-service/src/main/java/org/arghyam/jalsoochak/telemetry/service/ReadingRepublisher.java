package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Publishes {@code METER_READING_RECORDED} for a reading that is already stored, after a path has
 * written or changed it outside {@code BfmReadingService.createReading}.
 *
 * <p>The event is built from the row as it stands in {@code flow_reading_table}, read back by id, so
 * analytics receives exactly what telemetry holds, whichever path wrote it. It carries the row's id
 * and {@code updated_at}, so analytics updates the submission's one fact row instead of adding
 * another, and ignores an older version that arrives late. An ELM or PDU reading also carries a fresh
 * snapshot of what its water quantity is calculated from.
 *
 * <p>SUPPLY-PLAUSIBILITY: a row that is still quarantined is not published, as on the submission
 * path. Telemetry keeps it out of every baseline, so publishing it would put a reading in analytics
 * that telemetry does not count. A path that means to release a row clears the marker first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingRepublisher {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final CalculationParametersSnapshotter calculationParametersSnapshotter;

    /** What {@link #republishAndAwait} did with a reading. */
    public enum Result {
        /** Kafka acknowledged the event. */
        PUBLISHED,
        /** Not sent, because the row is still quarantined. */
        WITHHELD,
        /** Kafka did not acknowledge the event: the send failed or timed out, so it may or may not arrive. */
        NOT_ACKNOWLEDGED
    }

    /**
     * Queues the event on the publisher's executor and returns at once.
     *
     * @param tenantId the tenant for the published event; analytics drops the attendance and
     *                 water-quantity facts of an event without one
     * @throws IllegalStateException when the row cannot be read back, which means it was deleted
     *                               between the caller's write and this read
     */
    public void republish(String schemaName, Integer tenantId, Long readingId) {
        publishableEvent(schemaName, tenantId, readingId)
                .ifPresent(telemetryEventPublisher::publishMeterReadingRecorded);
    }

    /**
     * Publishes on the calling thread and waits for Kafka to acknowledge the event, for a caller that
     * republishes many readings in a row. See
     * {@link TelemetryEventPublisher#publishMeterReadingRecordedAndAwait}.
     *
     * @throws IllegalStateException when the row cannot be read back
     */
    public Result republishAndAwait(String schemaName, Integer tenantId, Long readingId) {
        Optional<MeterReadingEvent> event = publishableEvent(schemaName, tenantId, readingId);
        if (event.isEmpty()) {
            return Result.WITHHELD;
        }
        return telemetryEventPublisher.publishMeterReadingRecordedAndAwait(event.get())
                ? Result.PUBLISHED
                : Result.NOT_ACKNOWLEDGED;
    }

    /** The event for the stored row, or empty when the row is withheld because it is still quarantined. */
    private Optional<MeterReadingEvent> publishableEvent(String schemaName, Integer tenantId, Long readingId) {
        TelemetryLatestFlowReadingRecord reading = telemetryTenantRepository
                .findFlowReadingById(schemaName, readingId)
                .orElseThrow(() -> new IllegalStateException(
                        "Flow reading " + readingId + " not found in " + schemaName));

        Integer quarantineReason = reading.quarantineReason();
        if (quarantineReason != null && quarantineReason != QuarantineReason.NONE) {
            log.info("reading_republish_withheld reason=\"quarantined\" readingId={} schemeId={} quarantineReason={}",
                    readingId, reading.schemeId(), quarantineReason);
            return Optional.empty();
        }

        LocalDateTime readingAt = reading.readingAt() != null ? reading.readingAt() : ReadingTime.now();
        LocalDate readingDate = reading.readingDate() != null ? reading.readingDate() : readingAt.toLocalDate();
        return Optional.of(TelemetryEventPublisher.meterReadingRecordedEvent(
                tenantId,
                reading.schemeId(),
                reading.createdBy(),
                publishableExtractedReading(reading.extractedReading()),
                reading.confirmedReading(),
                // What tells analytics a kVAh reading from a kWh one; null on a pre-V56 row.
                reading.submittedUnit(),
                null,
                reading.imageUrl(),
                readingAt,
                // The channel stored at submission, so a correction keeps it and analytics does not
                // recompute the water quantity with another calculator. NULL on a legacy row, which
                // analytics treats as BFM.
                reading.channel(),
                readingDate,
                1,
                0,
                // ANOMALY-SUBMISSION-LINK: a republish carries the row's own correlation id, so the
                // warehouse keeps pointing at one submission.
                reading.correlationId(),
                reading.id(),
                // Read in the same statement as the values above, so the version always matches them.
                reading.updatedAt(),
                // Taken now rather than kept from the first publish: a corrected reading is calculated
                // with the pump data current at the time of the correction.
                calculationParametersSnapshotter.snapshot(
                        schemaName, tenantId, reading.schemeId(), ReadingChannel.fromCode(reading.channel()))
        ));
    }

    /**
     * The extracted reading to publish for a stored row. {@code extracted_reading} is NOT NULL, so every
     * row whose value did not come from OCR carries a 0 sentinel — an API submission that supplied
     * confirmed_reading, a hand-typed reading that opened the row, a reused placeholder. Republishing that
     * 0 would file the row under "operator overrode the AI" (extracted <> confirmed) on the dashboards,
     * which needs an AI reading to have existed; null keeps it out of both buckets. A row that really was
     * extracted always has a positive value, so nothing legitimate is suppressed.
     *
     * <p>Rows written before that sentinel was introduced still hold the supplied value and keep
     * publishing it — this is forward-only, with no backfill.
     */
    private static BigDecimal publishableExtractedReading(BigDecimal storedExtractedReading) {
        return storedExtractedReading == null || storedExtractedReading.signum() == 0
                ? null
                : storedExtractedReading;
    }
}
