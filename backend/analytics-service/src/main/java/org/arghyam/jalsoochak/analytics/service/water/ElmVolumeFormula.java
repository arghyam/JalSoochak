package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;

import java.math.BigDecimal;
import java.util.Set;

/**
 * One of the formulas a tenant can choose ({@code ELM_WATER_QUANTITY_FORMULA}) to turn an electricity
 * meter's kWh into litres of water pumped.
 *
 * <p>Register a new implementation as a Spring bean to support a new formula;
 * {@link ElmWaterQuantityCalculator} picks it up by its {@link #code()}. The calculator applies the
 * scheme's {@code k_factor} and rounds, so a formula does neither.
 */
public interface ElmVolumeFormula {

    /** The code the tenant config stores, as the snapshot carries it ({@code F1}, {@code F2}, ...). */
    String code();

    /** The pump parameters {@link #litres} reads; nothing else is asked of the pumps. */
    Set<PumpParameter> parameters();

    /**
     * @param kilowattHours the energy used, never negative
     * @param pumps         the {@link #parameters()} averaged over the scheme's active pumps, each
     *                      greater than 0
     * @return the litres pumped, unrounded
     */
    BigDecimal litres(BigDecimal kilowattHours, Available pumps);
}
