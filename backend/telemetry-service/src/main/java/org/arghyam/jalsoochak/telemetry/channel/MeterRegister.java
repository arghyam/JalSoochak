package org.arghyam.jalsoochak.telemetry.channel;

/**
 * Which of a meter's running totals a reading was taken from, worked out from the reading's
 * {@code submitted_unit}.
 *
 * <p>Two readings on different registers are different quantities, so one is never compared with the
 * other. Units that only scale the same quantity (a flow meter's {@code L} and {@code m3}) are
 * converted to the channel's standard unit before they are stored, so they share a register.
 * analytics-service has its own copy of this rule; the two must agree.
 */
public enum MeterRegister {

    /** The channel's standard unit: m&sup3; for BFM, kWh for ELM, minutes for PDU. */
    STANDARD,

    /**
     * An electricity meter's apparent-energy register, in kV&middot;A&middot;h. Stored as the meter
     * shows it, because only the pumps' power factor turns it into kWh, and analytics does that.
     */
    APPARENT_ENERGY;

    /**
     * @param submittedUnit a stored {@code submitted_unit}; null for a reading in the channel's
     *                      standard unit, and for every reading stored before the unit was recorded
     */
    public static MeterRegister of(String submittedUnit) {
        return ReadingUnit.KILOVOLT_AMPERE_HOUR.code().equals(submittedUnit) ? APPARENT_ENERGY : STANDARD;
    }
}
