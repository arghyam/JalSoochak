package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The inputs an ELM or PDU reading's water quantity is calculated from, snapshotted by
 * telemetry-service when it publishes the reading. Null for BFM.
 *
 * <p>Analytics reads only {@code analytics_schema}, so it cannot look pump data up itself. The
 * snapshot is stored on the fact row ({@code calculation_parameters}) because days are recalculated
 * outside the event that carried it. Telemetry keeps its own copy of this contract; this one only
 * has to read it.
 *
 * <p>Values are exactly what telemetry stores. Filtering, unit conversion, validation and averaging
 * happen in analytics, so every calculation rule lives in one service.
 *
 * @param version       contract version, currently 1
 * @param elmFormula    the tenant's ELM formula code ({@code F1}/{@code F2}/{@code F3}); null when
 *                      the tenant has not set one, and for PDU
 * @param kFactor       the scheme's correction factor; null means 1
 * @param pumps         the scheme's active pumps
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CalculationParameters(
        Integer version,
        String elmFormula,
        BigDecimal kFactor,
        List<Pump> pumps
) {

    /** One active pump's raw parameters; any of them may be null. */
    @JsonIgnoreProperties(ignoreUnknown = true)
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
