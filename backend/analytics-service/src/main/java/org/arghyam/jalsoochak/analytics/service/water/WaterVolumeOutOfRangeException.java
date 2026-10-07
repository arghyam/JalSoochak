package org.arghyam.jalsoochak.analytics.service.water;

import java.math.BigDecimal;

/**
 * A derived volume too large to store in {@code fact_water_quantity_table.water_quantity}
 * ({@code BIGINT} litres).
 *
 * <p>Reachable from real input: meter readings are unbounded {@code NUMERIC} on both sides of the
 * topic, and the submission API bounds them only from below
 * ({@code CanonicalReadingRequest.confirmedReading} carries {@code @DecimalMin} and no maximum), so a
 * mis-read or mis-typed reading can produce a delta whose litre value exceeds {@code long}.
 *
 * <p>It is a distinct type so the ingestion boundary can catch <em>this</em> rather than any
 * {@link ArithmeticException} a channel calculator might raise for an unrelated reason. It extends
 * {@code ArithmeticException} because that is what an overflowing conversion has always thrown here,
 * and callers outside this package should not have to care which of the two they see.
 */
public class WaterVolumeOutOfRangeException extends ArithmeticException {

    private final transient BigDecimal value;
    private final String unit;

    /**
     * @param value the offending quantity, in the unit it was in when it overflowed
     * @param unit  that unit, for the log line (e.g. {@code m3} for a meter delta, {@code L} for a sum)
     */
    public WaterVolumeOutOfRangeException(BigDecimal value, String unit) {
        super("Water volume " + value + " " + unit + " converts to more litres than a BIGINT column holds");
        this.value = value;
        this.unit = unit;
    }

    /** The offending quantity — the number worth putting in the log. */
    public BigDecimal getValue() {
        return value;
    }

    /** The unit {@link #getValue()} is in. */
    public String getUnit() {
        return unit;
    }
}
