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
        Integer current = context.currentReading();
        Integer previous = context.previousReading();
        // A null previous reading means no baseline exists yet, not "the meter was at zero". A
        // cumulative index is a running total, so without something to subtract there is no derivable
        // volume for the day — the honest answer is 0. Treating the absence as 0 instead would write
        // the entire meter index as one day's supply, which is how first-ever and post-gap readings
        // came to hold values in the millions.
        if (current == null || previous == null) {
            return 0L;
        }
        // Subtract as long: the readings are Integer, so an int subtraction could wrap on extreme values.
        return WaterVolumeUnits.cubicMetresToLitres(Math.max(0L, (long) current - previous));
    }
}
