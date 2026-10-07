package org.arghyam.jalsoochak.analytics.service.water;

/**
 * A pump value a water-quantity formula can use, in the one unit {@link PumpParameterAggregator}
 * delivers it in.
 */
public enum PumpParameter {

    /** {@code pump_discharge_capacity}, litres per minute. */
    DISCHARGE_CAPACITY_LPM(false),

    /** {@code units_consumed_per_hour}, the kWh the pump uses in an hour. */
    UNITS_CONSUMED_PER_HOUR(false),

    /** {@code motor_power}, converted to kW from its {@code motor_power_unit}. */
    MOTOR_POWER_KW(false),

    /** {@code pump_efficiency}, a fraction. */
    PUMP_EFFICIENCY(true),

    /** {@code motor_efficiency}, a fraction. */
    MOTOR_EFFICIENCY(true),

    /** {@code pump_head}, metres. */
    PUMP_HEAD_M(false);

    private final boolean fraction;

    PumpParameter(boolean fraction) {
        this.fraction = fraction;
    }

    /** Whether a value above 1 is out of range: efficiencies are stored as fractions, not percentages. */
    public boolean isFraction() {
        return fraction;
    }
}
