package org.arghyam.jalsoochak.telemetry.dto.event;

import java.math.BigDecimal;
import java.util.List;

/**
 * The inputs an ELM or PDU reading's water quantity is calculated from, taken when the reading is
 * published. Null for every other channel.
 *
 * <p>Analytics reads only its own schema, so it cannot look pump data up itself. It stores this
 * snapshot on the reading's fact row and calculates from it, including when it recalculates the day
 * later. Analytics keeps its own copy of this contract.
 *
 * <p>Values are exactly what the tenant schema stores. Filtering, unit conversion, validation and
 * averaging happen in analytics, so every calculation rule lives in one service.
 *
 * @param version    contract version, {@link #VERSION}
 * @param elmFormula the tenant's ELM formula code ({@code F1}/{@code F2}/{@code F3}); null when the
 *                   tenant has not set a readable one, and for PDU
 * @param kFactor    {@code scheme_master_table.k_factor}; null means 1
 * @param pumps      the scheme's active pumps
 */
public record CalculationParameters(
        Integer version,
        String elmFormula,
        BigDecimal kFactor,
        List<Pump> pumps
) {

    public static final int VERSION = 1;

    /** One active pump's row of {@code asset_pump_registry_table}; any value may be null. */
    public record Pump(
            Long pumpId,
            BigDecimal pumpDischargeCapacityLpm,
            BigDecimal pumpEfficiency,
            BigDecimal pumpHeadM,
            BigDecimal motorPower,
            String motorPowerUnit,
            BigDecimal motorEfficiency,
            BigDecimal unitsConsumedPerHour
    ) {
    }
}
