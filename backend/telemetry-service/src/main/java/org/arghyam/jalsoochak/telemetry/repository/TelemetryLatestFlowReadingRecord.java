package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The flow-reading row a correction resolves to, by correlation id, by the submitter's phone or as the
 * operator's latest earlier reading, and the row a republish reads back by id.
 *
 * @param channel the row's {@code channel_id}, a {@link org.arghyam.jalsoochak.telemetry.channel.ReadingChannel}
 *        code; {@code null} on a BFM reading from before the channel was recorded
 * @param quarantineReason SUPPLY-PLAUSIBILITY: the row's quarantine marker, {@code null} on a
 *        pre-V40 tenant schema where the column does not exist (and where the plausibility check is
 *        skipped entirely anyway). It exists to <strong>select the anomaly reason text</strong> for a
 *        refused correction — whether the standing value is published or quarantined is what a
 *        reviewer needs to know — and for nothing else. The write decision must not branch on it: a
 *        refused correction writes nothing and an accepted one clears the flag unconditionally,
 *        whatever the row's prior state. Keeping it out of the control flow is what stops
 *        state-dependent branching, and the store divergence it caused, from creeping back in.
 * @param updatedAt the row's version, read in the same statement as the values beside it, so a
 *        republish never pairs one write's values with another write's version
 */
public record TelemetryLatestFlowReadingRecord(
        Long id,
        Long schemeId,
        Long createdBy,
        String correlationId,
        BigDecimal extractedReading,
        BigDecimal confirmedReading,
        String imageUrl,
        LocalDate readingDate,
        LocalDateTime readingAt,
        Integer channel,
        Integer quarantineReason,
        LocalDateTime updatedAt
) {
}
