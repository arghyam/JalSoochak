package org.arghyam.jalsoochak.analytics.service.water;

import java.math.BigDecimal;

/**
 * A derived volume too large to store in {@code fact_water_quantity_table.water_quantity}
 * ({@code BIGINT} litres).
 *
 * <p>Reachable from real input: meter readings are unbounded {@code NUMERIC} on both sides of the
 * topic, and the submission API bounds them only from below
 * ({@code AssamReadingRequest.confirmedReading} carries {@code @DecimalMin} and no maximum), so a
 * mis-read or mis-typed reading can produce a delta whose litre value exceeds {@code long}.
 *
 * <p>It is a distinct type so the ingestion boundary can catch <em>this</em> rather than any
 * {@link ArithmeticException} a channel calculator might raise for an unrelated reason. It extends
 * {@code ArithmeticException} because that is what an overflowing conversion has always thrown here,
 * and callers outside this package should not have to care which of the two they see.
 */
public class WaterVolumeOutOfRangeException extends ArithmeticException {

    private final transient BigDecimal cubicMetres;

    public WaterVolumeOutOfRangeException(BigDecimal cubicMetres) {
        super("Water volume " + cubicMetres + " m3 converts to more litres than a BIGINT column holds");
        this.cubicMetres = cubicMetres;
    }

    /** The offending volume, in the meter's native m&sup3; — the number worth putting in the log. */
    public BigDecimal getCubicMetres() {
        return cubicMetres;
    }
}
