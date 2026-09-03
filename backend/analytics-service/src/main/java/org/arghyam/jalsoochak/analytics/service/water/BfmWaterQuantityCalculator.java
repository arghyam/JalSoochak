package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.springframework.stereotype.Component;

/**
 * Default water-quantity calculator for bulk flow meters (cumulative meters).
 *
 * <p>Water quantity for the day is the increase over the previous reading, converted from the meter's
 * native cubic metres to the litres the warehouse stores:
 * {@code max(0, currentReading - previousReading) * 1000}.
 *
 * <p>The conversion goes through {@link WaterVolumeUnits} so this path and the telemetry-correction
 * path in {@code FactServiceImpl.ingestWaterQuantity} cannot drift apart.
 */
@Component
public class BfmWaterQuantityCalculator implements WaterQuantityCalculator {

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.BFM;
    }

    @Override
    public long calculate(WaterQuantityContext context) {
        long current = context.currentReading() != null ? context.currentReading() : 0L;
        long previous = context.previousReading() != null ? context.previousReading() : 0L;
        // Subtract as long: the readings are Integer, so an int subtraction could wrap on extreme values.
        return WaterVolumeUnits.cubicMetresToLitres(Math.max(0L, current - previous));
    }
}
