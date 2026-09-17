package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;

/**
 * Per-channel strategy for deriving the day's water quantity from a meter reading.
 *
 * <p>Register a new implementation as a Spring bean to support a new channel;
 * {@link WaterQuantityCalculatorRegistry} picks it up automatically. The default
 * {@link BfmWaterQuantityCalculator} reproduces the historical cumulative-delta
 * behaviour and is used for {@link ReadingChannel#BFM} and for {@code null}/unknown
 * channel codes. An explicitly non-default channel with no registered calculator is
 * <em>not</em> silently treated as BFM (see {@link WaterQuantityCalculatorRegistry#resolve(Integer)}).
 */
public interface WaterQuantityCalculator {

    /** The channel this calculator handles. */
    ReadingChannel channel();

    /**
     * Derives the (non-negative) water quantity for the reading's day from the
     * {@link WaterQuantityContext}. The calculator owns the interpretation of the
     * context's reading values for its channel (see {@link WaterQuantityContext}).
     *
     * <p>The return value is in <strong>litres</strong>, the unit
     * {@code fact_water_quantity_table.water_quantity} is denominated in — regardless of the unit the
     * channel's readings are expressed in. Each calculator normalises its own channel's unit on the
     * way out; BFM does so through {@link WaterVolumeUnits}. It is {@code long} because a m&sup3;
     * delta multiplied into litres does not reliably fit an {@code int}.
     *
     * @param context the reading inputs (readings, scheme identifiers, channel)
     * @return the water quantity to persist, in litres (never negative)
     */
    long calculate(WaterQuantityContext context);
}
