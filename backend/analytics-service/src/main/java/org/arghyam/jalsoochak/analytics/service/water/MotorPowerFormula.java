package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.DISCHARGE_CAPACITY_LPM;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.MOTOR_POWER_KW;

/**
 * ELM formula F2: {@code kWh × LPM × 60 / motor_kW}. The kWh over the motor's power is the hours it
 * ran, times the pump's discharge rate. The motor power must be in kW for the division to give hours,
 * which {@link PumpParameterAggregator} sees to.
 */
@Component
public class MotorPowerFormula implements ElmVolumeFormula {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private static final Set<PumpParameter> PARAMETERS =
            Collections.unmodifiableSet(EnumSet.of(DISCHARGE_CAPACITY_LPM, MOTOR_POWER_KW));

    @Override
    public String code() {
        return "F2";
    }

    @Override
    public Set<PumpParameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public BigDecimal litres(BigDecimal kilowattHours, Available pumps) {
        return kilowattHours
                .multiply(pumps.get(DISCHARGE_CAPACITY_LPM))
                .multiply(MINUTES_PER_HOUR)
                .divide(pumps.get(MOTOR_POWER_KW), MathContext.DECIMAL64);
    }
}
