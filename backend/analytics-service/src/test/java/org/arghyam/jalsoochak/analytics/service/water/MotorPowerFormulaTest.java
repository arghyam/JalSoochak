package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.DISCHARGE_CAPACITY_LPM;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.MOTOR_POWER_KW;
import static org.assertj.core.api.Assertions.assertThat;

/** The HP and bHP worked examples, converted by the aggregator, are in {@code ElmWaterQuantityCalculatorTest}. */
class MotorPowerFormulaTest {

    private final MotorPowerFormula formula = new MotorPowerFormula();

    @Test
    void isF2AndNeedsTheDischargeRateAndTheMotorPowerInKilowatts() {
        assertThat(formula.code()).isEqualTo("F2");
        assertThat(formula.parameters()).containsExactlyInAnyOrder(DISCHARGE_CAPACITY_LPM, MOTOR_POWER_KW);
    }

    @Test
    void litres_workedExample() {
        // 7.5 HP = 5.59275 kW; 10 kWh / 5.59275 kW = 1.788 h; x 60 x 500 LPM = 53,640.87 L
        assertThat(WaterVolumeUnits.wholeLitres(formula.litres(BigDecimal.TEN, pumps("5.59275"))))
                .isEqualTo(53_641L);
    }

    @Test
    void litres_workedExampleInBhp() {
        // 7.5 bHP = 5.516129325 kW
        assertThat(WaterVolumeUnits.wholeLitres(formula.litres(BigDecimal.TEN, pumps("5.516129325"))))
                .isEqualTo(54_386L);
    }

    private static Available pumps(String motorKilowatts) {
        return new Available(Map.of(
                DISCHARGE_CAPACITY_LPM, new BigDecimal("500"),
                MOTOR_POWER_KW, new BigDecimal(motorKilowatts)));
    }
}
