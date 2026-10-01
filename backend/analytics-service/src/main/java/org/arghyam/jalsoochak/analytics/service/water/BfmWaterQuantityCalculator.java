package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.springframework.stereotype.Component;

/**
 * Water-quantity calculator for bulk flow meters. The amount is already the day's volume in the
 * meter's cubic metres, so this is only the conversion to the litres the warehouse stores.
 *
 * <p>The conversion goes through {@link WaterVolumeUnits} so this path and the telemetry-correction
 * path in {@code FactServiceImpl.ingestWaterQuantity} cannot drift apart. BFM always has a result.
 */
@Component
public class BfmWaterQuantityCalculator implements WaterQuantityCalculator {

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.BFM;
    }

    @Override
    public WaterQuantityOutcome calculate(WaterQuantityContext context) {
        return WaterQuantityOutcome.derived(WaterVolumeUnits.cubicMetresToLitres(context.amount()));
    }
}
