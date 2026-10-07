package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.MOTOR_EFFICIENCY;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.PUMP_EFFICIENCY;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.PUMP_HEAD_M;
import static org.assertj.core.api.Assertions.assertThat;

class HydraulicEnergyFormulaTest {

    private final HydraulicEnergyFormula formula = new HydraulicEnergyFormula();

    @Test
    void isF3AndNeedsBothEfficienciesAndTheHead() {
        assertThat(formula.code()).isEqualTo("F3");
        assertThat(formula.parameters()).containsExactlyInAnyOrder(PUMP_EFFICIENCY, MOTOR_EFFICIENCY, PUMP_HEAD_M);
    }

    @Test
    void litres_workedExample() {
        // 366.97 x 10 kWh x 0.70 x 0.85 / 40 m = 54.5867875 m3 = 54,586.7875 L
        Available pumps = new Available(Map.of(
                PUMP_EFFICIENCY, new BigDecimal("0.70"),
                MOTOR_EFFICIENCY, new BigDecimal("0.85"),
                PUMP_HEAD_M, new BigDecimal("40")));

        assertThat(formula.litres(BigDecimal.TEN, pumps)).isEqualByComparingTo("54586.7875");
        assertThat(WaterVolumeUnits.wholeLitres(formula.litres(BigDecimal.TEN, pumps))).isEqualTo(54_587L);
    }
}
