package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One confirmed meter reading per calendar day (the last submitted value for that day), used to build
 * the rollover-resolution consumption band. Deliberately separate from
 * {@link TelemetryConfirmedReadingSnapshot} so the shared snapshot record used by other queries is
 * left undisturbed.
 */
public record DailyConfirmedReading(
        LocalDate day,
        BigDecimal confirmedReading
) {
}
