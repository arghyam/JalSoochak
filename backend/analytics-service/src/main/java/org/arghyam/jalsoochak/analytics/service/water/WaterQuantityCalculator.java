package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;

/**
 * Per-channel strategy for turning an amount of the channel's quantity into litres.
 *
 * <p>Register a new implementation as a Spring bean to support a new channel;
 * {@link WaterQuantityCalculatorRegistry} picks it up automatically. How much of the quantity a day
 * has is not the calculator's concern: {@link WaterQuantityRecalculationService} works that out
 * from the channel's {@link ReadingChannel#kind() kind} and passes it in as
 * {@link WaterQuantityContext#amount()}.
 */
public interface WaterQuantityCalculator {

    /** The channel this calculator handles. */
    ReadingChannel channel();

    /**
     * Turns {@link WaterQuantityContext#amount()} into litres, the unit
     * {@code fact_water_quantity_table.water_quantity} is denominated in.
     *
     * @param context the amount in the channel's standard unit, and the snapshot it belongs to
     * @return the litres, or why there are none
     * @throws WaterVolumeOutOfRangeException if the litres do not fit the {@code BIGINT} column
     */
    WaterQuantityOutcome calculate(WaterQuantityContext context);
}
