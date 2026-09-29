package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Publishes {@code METER_READING_RECORDED} for a reading that is already stored, after a path has
 * written or changed it outside {@code BfmReadingService.createReading}.
 *
 * <p>The event is built from the row as it stands in {@code flow_reading_table}, read back by id, so
 * analytics receives exactly what telemetry holds, whichever path wrote it.
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

    /**
     * @param tenantId the tenant for the published event; analytics drops the attendance and
     *                 water-quantity facts of an event without one
     * @throws IllegalStateException when the row cannot be read back, which means it was deleted
     *                               between the caller's write and this read
     */
    public void republish(String schemaName, Integer tenantId, Long readingId) {
        TelemetryLatestFlowReadingRecord reading = telemetryTenantRepository
                .findFlowReadingById(schemaName, readingId)
                .orElseThrow(() -> new IllegalStateException(
                        "Flow reading " + readingId + " not found in " + schemaName));

        Integer quarantineReason = reading.quarantineReason();
        if (quarantineReason != null && quarantineReason != QuarantineReason.NONE) {
            log.info("reading_republish_withheld reason=\"quarantined\" readingId={} schemeId={} quarantineReason={}",
                    readingId, reading.schemeId(), quarantineReason);
            return;
        }

        LocalDateTime readingAt = reading.readingAt() != null ? reading.readingAt() : ReadingTime.now();
        LocalDate readingDate = reading.readingDate() != null ? reading.readingDate() : readingAt.toLocalDate();
        telemetryEventPublisher.publishMeterReadingRecorded(
                tenantId,
                reading.schemeId(),
                reading.createdBy(),
                publishableExtractedReading(reading.extractedReading()),
                reading.confirmedReading(),
                null,
                reading.imageUrl(),
                readingAt,
                channelCode(reading),
                readingDate,
                1,
                0,
                // ANOMALY-SUBMISSION-LINK: a republish carries the row's own correlation id, so the
                // warehouse keeps pointing at one submission.
                reading.correlationId()
        );
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
    static BigDecimal publishableExtractedReading(BigDecimal storedExtractedReading) {
        return storedExtractedReading == null || storedExtractedReading.signum() == 0
                ? null
                : storedExtractedReading;
    }

    /**
     * Re-uses the channel persisted on the reading at submission so corrections keep the
     * original channel (BFM/ELM/PDU...) and analytics does not recompute the water quantity
     * with a different calculator. Returns {@code null} for legacy rows that never stored a
     * channel, which analytics treats as the default (BFM).
     */
    static Integer channelCode(TelemetryLatestFlowReadingRecord reading) {
        String channelValue = reading.channel();
        if (channelValue == null || channelValue.isBlank()) {
            return null;
        }
        return ReadingChannel.fromChannelValue(channelValue).getCode();
    }
}
