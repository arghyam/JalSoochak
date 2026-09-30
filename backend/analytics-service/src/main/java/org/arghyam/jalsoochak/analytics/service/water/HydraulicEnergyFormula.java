package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.MOTOR_EFFICIENCY;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.PUMP_EFFICIENCY;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.PUMP_HEAD_M;

/**
 * ELM formula F3: {@code V(m³) = 366.97 × kWh × Np × Nm / H}, in litres. The share of the energy
 * that reaches the water, over the work needed to lift one m³ by the pump's head.
 */
@Component
public class HydraulicEnergyFormula implements ElmVolumeFormula {

    /** 3.6×10⁶ J per kWh / (1000 kg/m³ × 9.81 m/s²), rounded as the spec gives it. */
    static final BigDecimal CUBIC_METRE_METRES_PER_KILOWATT_HOUR = new BigDecimal("366.97");

    private static final BigDecimal LITRES_PER_CUBIC_METRE =
            BigDecimal.valueOf(WaterVolumeUnits.LITRES_PER_CUBIC_METRE);

    private static final Set<PumpParameter> PARAMETERS =
            Collections.unmodifiableSet(EnumSet.of(PUMP_EFFICIENCY, MOTOR_EFFICIENCY, PUMP_HEAD_M));

    @Override
    public String code() {
        return "F3";
    }

    @Override
    public Set<PumpParameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public BigDecimal litres(BigDecimal kilowattHours, Available pumps) {
        return CUBIC_METRE_METRES_PER_KILOWATT_HOUR
                .multiply(kilowattHours)
                .multiply(pumps.get(PUMP_EFFICIENCY))
                .multiply(pumps.get(MOTOR_EFFICIENCY))
                .multiply(LITRES_PER_CUBIC_METRE)
                .divide(pumps.get(PUMP_HEAD_M), MathContext.DECIMAL64);
    }
}
