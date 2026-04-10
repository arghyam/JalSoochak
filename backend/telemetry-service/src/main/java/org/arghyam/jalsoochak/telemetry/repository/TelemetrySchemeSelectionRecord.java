package org.arghyam.jalsoochak.telemetry.repository;

public record TelemetrySchemeSelectionRecord(
        Long id,
        Long schemeId,
        String correlationId
) {
}
