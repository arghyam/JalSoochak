package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;

/**
 * @param submittedUnit the row's {@code submitted_unit}, which also names its
 *        {@link org.arghyam.jalsoochak.telemetry.channel.MeterRegister}; {@code null} on a row from
 *        before the unit was recorded and on a pre-V56 tenant schema
 */
public record TelemetryFlowReadingDetails(
        Long id,
        String correlationId,
        Long createdBy,
        BigDecimal extractedReading,
        BigDecimal confirmedReading,
        String submittedUnit
) {
}

