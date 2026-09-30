package org.arghyam.jalsoochak.analytics.service.water;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Unavailable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.DISCHARGE_CAPACITY_LPM;

/**
 * Water-quantity calculator for pump run durations: one run's minutes times the pump's discharge rate
 * in litres per minute. The day's total is the sum over its runs, added up by
 * {@link WaterQuantityRecalculationService}.
 *
 * <p>The scheme's {@code k_factor} corrects the ELM formulas only, so it is ignored here.
 */
@Component
@RequiredArgsConstructor
public class PduWaterQuantityCalculator implements WaterQuantityCalculator {

    private static final Set<PumpParameter> PARAMETERS =
            Collections.unmodifiableSet(EnumSet.of(DISCHARGE_CAPACITY_LPM));

    private final PumpParameterAggregator pumpParameterAggregator;

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.PDU;
    }

    @Override
    public WaterQuantityOutcome calculate(WaterQuantityContext context) {
        CalculationParameters parameters = context.parameters();
        return switch (pumpParameterAggregator.average(parameters == null ? null : parameters.pumps(), PARAMETERS)) {
            case Unavailable unavailable -> WaterQuantityOutcome.notDerivable(unavailable.reason());
            case Available pumps -> WaterQuantityOutcome.derived(WaterVolumeUnits.wholeLitres(
                    context.amount().multiply(pumps.get(DISCHARGE_CAPACITY_LPM))));
        };
    }
}
