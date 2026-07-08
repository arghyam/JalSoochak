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
     * @param context the reading inputs (readings, scheme identifiers, channel)
     * @return the water quantity to persist (never negative)
     */
    int calculate(WaterQuantityContext context);
}
