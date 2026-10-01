package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters.Pump;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Unavailable;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.arghyam.jalsoochak.analytics.service.water.PumpFixture.pump;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.DISCHARGE_CAPACITY_LPM;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.MOTOR_EFFICIENCY;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.MOTOR_POWER_KW;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.PUMP_EFFICIENCY;
import static org.arghyam.jalsoochak.analytics.service.water.PumpParameter.PUMP_HEAD_M;
import static org.assertj.core.api.Assertions.assertThat;

class PumpParameterAggregatorTest {

    private static final String UNKNOWN_UNIT_COUNTER = "water_quantity.motor_power_unit.unknown";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PumpParameterAggregator aggregator = new PumpParameterAggregator(meterRegistry);

    // ---- no pumps ---------------------------------------------------------------------------

    @Test
    void average_noPumps_isNoActivePump() {
        assertThat(aggregator.average(List.of(), Set.of(DISCHARGE_CAPACITY_LPM)))
                .isEqualTo(new Unavailable(Reason.NO_ACTIVE_PUMP));
        assertThat(aggregator.average(null, Set.of(DISCHARGE_CAPACITY_LPM)))
                .isEqualTo(new Unavailable(Reason.NO_ACTIVE_PUMP));
    }

    // ---- averaging --------------------------------------------------------------------------

    @Test
    void average_onePump_isItsOwnValue() {
        assertThat(value(DISCHARGE_CAPACITY_LPM, pump(1).dischargeCapacityLpm("500"))).isEqualByComparingTo("500");
    }

    @Test
    void average_skipsPumpsWithNoValueForTheParameter() {
        assertThat(value(DISCHARGE_CAPACITY_LPM,
                pump(1).dischargeCapacityLpm("500"),
                pump(2).pumpHeadM("40"),
                pump(3).dischargeCapacityLpm("300")))
                .isEqualByComparingTo("400");
    }

    @Test
    void average_eachParameterOverItsOwnPumps() {
        Available averaged = available(aggregator.average(List.of(
                        pump(1).pumpEfficiency("0.7").pumpHeadM("40").build(),
                        pump(2).pumpEfficiency("0.5").motorEfficiency("0.85").build()),
                EnumSet.of(PUMP_EFFICIENCY, MOTOR_EFFICIENCY, PUMP_HEAD_M)));

        assertThat(averaged.get(PUMP_EFFICIENCY)).isEqualByComparingTo("0.6");
        assertThat(averaged.get(MOTOR_EFFICIENCY)).isEqualByComparingTo("0.85");
        assertThat(averaged.get(PUMP_HEAD_M)).isEqualByComparingTo("40");
    }

    @Test
    void average_noPumpHasTheParameter_isMissingParameter() {
        assertThat(aggregator.average(List.of(pump(1).pumpHeadM("40").build()), Set.of(DISCHARGE_CAPACITY_LPM)))
                .isEqualTo(new Unavailable(Reason.MISSING_PARAMETER));
    }

    @Test
    void average_readsOnlyTheRequestedParameters() {
        // An efficiency out of range doesn't matter to a formula that doesn't use it.
        assertThat(value(DISCHARGE_CAPACITY_LPM, pump(1).dischargeCapacityLpm("500").pumpEfficiency("70")))
                .isEqualByComparingTo("500");
    }

    @Test
    void average_theFirstFailingParameterInEnumOrderDecides() {
        // Discharge capacity comes before motor power: its absence is reported, not the bad motor power.
        assertThat(aggregator.average(List.of(pump(1).motorPower("0", "kW").build()),
                EnumSet.of(DISCHARGE_CAPACITY_LPM, MOTOR_POWER_KW)))
                .isEqualTo(new Unavailable(Reason.MISSING_PARAMETER));
    }

    // ---- out of range -----------------------------------------------------------------------

    @Test
    void average_efficiencyAboveOneOnOnePump_isInvalidEvenWhenTheAverageIsNot() {
        // The average of 1.2 and 0.6 is 0.9, which would pass if only the average were checked.
        assertThat(aggregator.average(List.of(
                        pump(1).pumpEfficiency("1.2").build(),
                        pump(2).pumpEfficiency("0.6").build()),
                Set.of(PUMP_EFFICIENCY)))
                .isEqualTo(new Unavailable(Reason.INVALID_PARAMETER));
    }

    @Test
    void average_efficiencyOfExactlyOne_isAccepted() {
        assertThat(value(MOTOR_EFFICIENCY, pump(1).motorEfficiency("1"))).isEqualByComparingTo("1");
    }

    @Test
    void average_valueAboveOneThatIsNotAnEfficiency_isAccepted() {
        assertThat(value(PUMP_HEAD_M, pump(1).pumpHeadM("40"))).isEqualByComparingTo("40");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void average_zeroOrNegativeValueOnOnePump_isInvalid(String bad) {
        assertThat(aggregator.average(List.of(
                        pump(1).dischargeCapacityLpm(bad).build(),
                        pump(2).dischargeCapacityLpm("500").build()),
                Set.of(DISCHARGE_CAPACITY_LPM)))
                .isEqualTo(new Unavailable(Reason.INVALID_PARAMETER));
        assertThat(aggregator.average(List.of(pump(1).pumpHeadM(bad).build()), Set.of(PUMP_HEAD_M)))
                .isEqualTo(new Unavailable(Reason.INVALID_PARAMETER));
        assertThat(aggregator.average(List.of(pump(1).pumpEfficiency(bad).build()), Set.of(PUMP_EFFICIENCY)))
                .isEqualTo(new Unavailable(Reason.INVALID_PARAMETER));
        assertThat(aggregator.average(List.of(pump(1).motorPower(bad, "kW").build()), Set.of(MOTOR_POWER_KW)))
                .isEqualTo(new Unavailable(Reason.INVALID_PARAMETER));
    }

    // ---- motor power units ------------------------------------------------------------------

    @Test
    void motorPower_inKilowatts_isUsedAsIs() {
        assertThat(value(MOTOR_POWER_KW, pump(1).motorPower("5", "kW"))).isEqualByComparingTo("5");
    }

    @Test
    void motorPower_inHp_isConvertedToKilowatts() {
        // 7.5 HP x 0.7457 = 5.59275 kW
        assertThat(value(MOTOR_POWER_KW, pump(1).motorPower("7.5", "HP"))).isEqualByComparingTo("5.59275");
    }

    @Test
    void motorPower_inBhp_isConvertedToHpThenToKilowatts() {
        // 7.5 bHP x 0.9863 = 7.39725 HP; x 0.7457 = 5.516129325 kW
        assertThat(value(MOTOR_POWER_KW, pump(1).motorPower("7.5", "bHP"))).isEqualByComparingTo("5.516129325");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kw", "KW", " kW ", "hp", "Hp", " HP", "bhp", "BHP", "bHp "})
    void motorPower_unitIsMatchedIgnoringCaseAndSurroundingSpaces(String unit) {
        assertThat(aggregator.average(List.of(pump(1).motorPower("7.5", unit).build()), Set.of(MOTOR_POWER_KW)))
                .isInstanceOf(Available.class);
        assertThat(meterRegistry.counter(UNKNOWN_UNIT_COUNTER).count()).isZero();
    }

    @Test
    void motorPower_isAveragedAfterEachPumpIsConvertedToKilowatts() {
        // (5.59275 + 5) / 2
        assertThat(value(MOTOR_POWER_KW,
                pump(1).motorPower("7.5", "HP"),
                pump(2).motorPower("5", "kW")))
                .isEqualByComparingTo("5.296375");
    }

    @Test
    void motorPower_inAnUnknownUnit_isMissingForThatPumpAndCounted() {
        assertThat(value(MOTOR_POWER_KW,
                pump(1).motorPower("7.5", "HP"),
                pump(2).motorPower("5000", "W")))
                .isEqualByComparingTo("5.59275");
        assertThat(meterRegistry.counter(UNKNOWN_UNIT_COUNTER).count()).isEqualTo(1.0);
    }

    @Test
    void motorPower_withNoUnit_isMissingAndCounted() {
        assertThat(aggregator.average(List.of(pump(1).motorPower("7.5", null).build()), Set.of(MOTOR_POWER_KW)))
                .isEqualTo(new Unavailable(Reason.MISSING_PARAMETER));
        assertThat(meterRegistry.counter(UNKNOWN_UNIT_COUNTER).count()).isEqualTo(1.0);
    }

    @Test
    void motorPower_absent_isMissingButNotCountedWhateverTheUnit() {
        assertThat(aggregator.average(List.of(pump(1).motorPower(null, "furlongs").build()), Set.of(MOTOR_POWER_KW)))
                .isEqualTo(new Unavailable(Reason.MISSING_PARAMETER));
        assertThat(meterRegistry.counter(UNKNOWN_UNIT_COUNTER).count()).isZero();
    }

    @Test
    void motorPower_notRequested_isNotCountedEvenInAnUnknownUnit() {
        aggregator.average(List.of(pump(1).dischargeCapacityLpm("500").motorPower("5000", "W").build()),
                Set.of(DISCHARGE_CAPACITY_LPM));

        assertThat(meterRegistry.counter(UNKNOWN_UNIT_COUNTER).count()).isZero();
    }

    private BigDecimal value(PumpParameter parameter, PumpFixture... pumps) {
        List<Pump> built = Arrays.stream(pumps).map(PumpFixture::build).toList();
        return available(aggregator.average(built, Set.of(parameter))).get(parameter);
    }

    private static Available available(AveragedPumpParameters averaged) {
        assertThat(averaged).isInstanceOf(Available.class);
        return (Available) averaged;
    }
}
