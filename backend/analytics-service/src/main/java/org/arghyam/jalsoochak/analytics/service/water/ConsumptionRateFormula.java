package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.DISCHARGE_CAPACITY_LPM;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.UNITS_CONSUMED_PER_HOUR;

/**
 * ELM formula F1: {@code kWh × LPM × 60 / units_consumed_per_hour}. The kWh over the pump's hourly
 * consumption is the hours it ran, times its discharge rate.
 */
@Component
public class ConsumptionRateFormula implements ElmVolumeFormula {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private static final Set<PumpParameter> PARAMETERS =
            Collections.unmodifiableSet(EnumSet.of(DISCHARGE_CAPACITY_LPM, UNITS_CONSUMED_PER_HOUR));

    @Override
    public String code() {
        return "F1";
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
                .divide(pumps.get(UNITS_CONSUMED_PER_HOUR), MathContext.DECIMAL64);
    }
}
