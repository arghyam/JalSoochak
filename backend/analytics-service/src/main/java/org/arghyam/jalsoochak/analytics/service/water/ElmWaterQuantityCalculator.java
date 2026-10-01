package org.arghyam.jalsoochak.analytics.service.water;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Unavailable;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Reason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Water-quantity calculator for electricity meters: the day's kWh through the tenant's chosen
 * {@link ElmVolumeFormula}, times the scheme's {@code k_factor}.
 *
 * <p>There is no default formula. A day whose snapshot names none, or a code no formula has, cannot be
 * calculated, whatever its kWh: the day is left without a total, and the metric shows the tenant's
 * configuration is missing.
 */
@Component
@Slf4j
public class ElmWaterQuantityCalculator implements WaterQuantityCalculator {

    private final Map<String, ElmVolumeFormula> formulasByCode = new HashMap<>();
    private final PumpParameterAggregator pumpParameterAggregator;

    public ElmWaterQuantityCalculator(List<ElmVolumeFormula> formulas, PumpParameterAggregator pumpParameterAggregator) {
        for (ElmVolumeFormula formula : formulas) {
            ElmVolumeFormula existing = formulasByCode.putIfAbsent(formula.code(), formula);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate ElmVolumeFormula registered for code " + formula.code()
                                + ": " + existing.getClass().getName() + " and " + formula.getClass().getName());
            }
        }
        this.pumpParameterAggregator = pumpParameterAggregator;
    }

    @Override
    public ReadingChannel channel() {
        return ReadingChannel.ELM;
    }

    @Override
    public WaterQuantityOutcome calculate(WaterQuantityContext context) {
        CalculationParameters parameters = context.parameters();
        if (parameters == null || !formulasByCode.containsKey(parameters.elmFormula())) {
            return WaterQuantityOutcome.notDerivable(Reason.MISSING_FORMULA);
        }
        ElmVolumeFormula formula = formulasByCode.get(parameters.elmFormula());
        // NULL means the scheme has no correction. A factor of 0 or less would give no water or
        // negative water, which is a data-entry error, not a measurement.
        BigDecimal kFactor = Objects.requireNonNullElse(parameters.kFactor(), BigDecimal.ONE);
        if (kFactor.signum() <= 0) {
            log.warn("k_factor {} is out of range (tenantId={}, schemeId={}); the water quantity cannot be calculated",
                    kFactor, context.tenantId(), context.schemeId());
            return WaterQuantityOutcome.notDerivable(Reason.INVALID_PARAMETER);
        }
        return switch (pumpParameterAggregator.average(parameters.pumps(), formula.parameters())) {
            case Unavailable unavailable -> WaterQuantityOutcome.notDerivable(unavailable.reason());
            case Available pumps -> WaterQuantityOutcome.derived(WaterVolumeUnits.wholeLitres(
                    formula.litres(context.amount(), pumps).multiply(kFactor)));
        };
    }
}
