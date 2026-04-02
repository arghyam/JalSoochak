package org.arghyam.jalsoochak.telemetry.repository;

public record TelemetryPendingIssueReportRecord(
        Long id,
        String correlationId,
        Long createdBy
) {
}
