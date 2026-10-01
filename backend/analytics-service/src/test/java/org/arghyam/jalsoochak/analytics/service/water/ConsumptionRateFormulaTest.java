package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.DISCHARGE_CAPACITY_LPM;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.UNITS_CONSUMED_PER_HOUR;
import static org.assertj.core.api.Assertions.assertThat;

class ConsumptionRateFormulaTest {

    private final ConsumptionRateFormula formula = new ConsumptionRateFormula();

    @Test
    void isF1AndNeedsTheDischargeRateAndTheHourlyConsumption() {
        assertThat(formula.code()).isEqualTo("F1");
        assertThat(formula.parameters()).containsExactlyInAnyOrder(DISCHARGE_CAPACITY_LPM, UNITS_CONSUMED_PER_HOUR);
    }

    @Test
    void litres_workedExample() {
        // 10 kWh / 5 kWh per hour = 2 h = 120 min; x 500 LPM = 60,000 L
        Available pumps = new Available(Map.of(
                DISCHARGE_CAPACITY_LPM, new BigDecimal("500"),
                UNITS_CONSUMED_PER_HOUR, new BigDecimal("5")));

        assertThat(formula.litres(BigDecimal.TEN, pumps)).isEqualByComparingTo("60000");
    }

    @Test
    void litres_keepsFullPrecisionForTheCallerToRound() {
        // 1 kWh x 500 x 60 / 7 = 4285.714285714286 (16 significant digits)
        Available pumps = new Available(Map.of(
                DISCHARGE_CAPACITY_LPM, new BigDecimal("500"),
                UNITS_CONSUMED_PER_HOUR, new BigDecimal("7")));

        assertThat(formula.litres(BigDecimal.ONE, pumps)).isEqualByComparingTo("4285.714285714286");
    }
}
