package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record TelemetryLatestFlowReadingRecord(
        Long id,
        Long schemeId,
        Long createdBy,
        String correlationId,
        BigDecimal extractedReading,
        BigDecimal confirmedReading,
        String imageUrl,
        LocalDate readingDate,
        LocalDateTime readingAt
) {
}
