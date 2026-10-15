package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @param createdAt when the row was written
 * @param readingAt when the reading was taken — the row's {@code observation_time}, or
 *        {@code reading_at} on a schema that predates it — which a reading's day is worked out from.
 *        It differs from {@code createdAt} for a reading submitted after the day it was taken.
 */
public record TelemetryConfirmedReadingSnapshot(
        BigDecimal confirmedReading,
        LocalDateTime createdAt,
        LocalDateTime readingAt
) {
}
