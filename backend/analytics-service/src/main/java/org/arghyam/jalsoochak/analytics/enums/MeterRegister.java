package org.arghyam.jalsoochak.analytics.enums;

/**
 * Which of a meter's running totals a reading was taken from, worked out from the reading's
 * {@code submitted_unit}.
 *
 * <p>Two readings on different registers are different quantities, so one is never the other's
 * starting point. Units that only scale the same quantity (a flow meter's {@code L} and {@code m3})
 * are converted to the standard unit by telemetry before they are stored, so they share a register.
 */
public enum MeterRegister {

    /** The channel's standard unit: m&sup3; for BFM, kWh for ELM, minutes for PDU. */
    STANDARD,

    /**
     * An electricity meter's apparent-energy register, in kV&middot;A&middot;h. Stored as the meter
     * shows it; the day's increase is turned into kWh with the pumps' power factor.
     */
    APPARENT_ENERGY;

    /** The UCUM code telemetry stores for kVAh. */
    static final String KILOVOLT_AMPERE_HOUR = "kV.A.h";

    /**
     * @param submittedUnit the reading's stored UCUM code; null for a reading in the channel's standard
     *                      unit, and for every reading stored before the unit was recorded
     */
    public static MeterRegister of(String submittedUnit) {
        return KILOVOLT_AMPERE_HOUR.equals(submittedUnit) ? APPARENT_ENERGY : STANDARD;
    }
}
