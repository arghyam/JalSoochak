package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TelemetryCompletedFlowReading(
        Long id,
        String correlationId,
        Long createdBy,
        LocalDate readingDate,
        BigDecimal confirmedReading
) {
}
