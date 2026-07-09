package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.springframework.stereotype.Component;

/**
 * Default water-quantity calculator for bulk flow meters (cumulative meters).
 *
 * <p>Water quantity for the day is the increase over the previous day's reading:
 * {@code max(0, currentReading - previousReading)}. This preserves the historical
 * behaviour previously inlined in {@code FactServiceImpl.updateWaterQuantityFromReading}.
 */
@Component
public class BfmWaterQuantityCalculator implements WaterQuantityCalculator {

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.BFM;
    }

    @Override
    public int calculate(WaterQuantityContext context) {
        int current = context.currentReading() != null ? context.currentReading() : 0;
        int previous = context.previousReading() != null ? context.previousReading() : 0;
        return Math.max(0, current - previous);
    }
}
