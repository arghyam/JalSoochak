package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;

/**
 * Per-channel strategy for deriving the day's water quantity from a meter reading.
 *
 * <p>Register a new implementation as a Spring bean to support a new channel;
 * {@link WaterQuantityCalculatorRegistry} picks it up automatically. The default
 * {@link BfmWaterQuantityCalculator} reproduces the historical cumulative-delta
 * behaviour and is used for {@link ReadingChannel#BFM} and any unmapped channel.
 */
public interface WaterQuantityCalculator {

    /** The channel this calculator handles. */
    ReadingChannel channel();

    /**
     * Derives the (non-negative) water quantity for the reading's day.
     *
     * @param currentReading  the reading recorded for the day (confirmed, else extracted); may be {@code null}
     * @param previousReading the most recent reading on the previous day; may be {@code null}
     * @return the water quantity to persist (never negative)
     */
    int calculate(Integer currentReading, Integer previousReading);
}
