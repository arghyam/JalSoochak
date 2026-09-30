package org.arghyam.jalsoochak.telemetry.repository;

import java.math.BigDecimal;

/**
 * An active pump of a scheme, from {@code asset_pump_registry_table}, with its ratings as stored. Any
 * rating may be null: none of them is required when a pump is registered.
 */
public record ActivePump(
        Long id,
        BigDecimal pumpDischargeCapacity,
        BigDecimal pumpEfficiency,
        BigDecimal pumpHead,
        BigDecimal motorPower,
        String motorPowerUnit,
        BigDecimal motorEfficiency,
        BigDecimal unitsConsumedPerHour
) {
}
