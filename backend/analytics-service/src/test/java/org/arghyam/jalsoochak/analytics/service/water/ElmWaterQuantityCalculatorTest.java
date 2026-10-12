package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters.Pump;
import org.arghyam.jalsoochak.analytics.enums.MeterRegister;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.arghyam.jalsoochak.analytics.service.water.PumpFixture.pump;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the production formulas and aggregator, so the worked examples of plan section 2.2 are checked
 * from the snapshot's raw values to the stored litres.
 */
class ElmWaterQuantityCalculatorTest {

    private static final Pump PUMP = pump(12)
            .dischargeCapacityLpm("500")
            .unitsConsumedPerHour("5")
            .motorPower("7.5", "HP")
            .pumpEfficiency("0.70")
            .motorEfficiency("0.85")
            .pumpHeadM("40")
            .build();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ElmWaterQuantityCalculator calculator = new ElmWaterQuantityCalculator(
            List.of(new ConsumptionRateFormula(), new MotorPowerFormula(), new HydraulicEnergyFormula()),
            new PumpParameterAggregator(meterRegistry));

    @Test
    void channel_isElm() {
        assertThat(calculator.channel()).isEqualTo(ReadingChannel.ELM);
    }

    // ---- worked examples (k = 1) ------------------------------------------------------------

    @Test
    void f1_workedExample() {
        assertThat(calculate("10", snapshot("F1", null, PUMP))).isEqualTo(derived(60_000L));
    }

    @Test
    void f2_workedExampleInHp() {
        assertThat(calculate("10", snapshot("F2", null, PUMP))).isEqualTo(derived(53_641L));
    }

    @Test
    void f2_workedExampleInBhp() {
        Pump bhp = pump(12).dischargeCapacityLpm("500").motorPower("7.5", "bHP").build();

        assertThat(calculate("10", snapshot("F2", null, bhp))).isEqualTo(derived(54_386L));
    }

    @Test
    void f3_workedExample() {
        assertThat(calculate("10", snapshot("F3", null, PUMP))).isEqualTo(derived(54_587L));
    }

    // ---- k_factor ---------------------------------------------------------------------------

    @Test
    void kFactor_multipliesTheFormulasLitres() {
        assertThat(calculate("10", snapshot("F1", "0.95", PUMP))).isEqualTo(derived(57_000L));
        // 53,640.874... x 0.9 = 48,276.79
        assertThat(calculate("10", snapshot("F2", "0.9", PUMP))).isEqualTo(derived(48_277L));
    }

    @Test
    void kFactor_isAppliedBeforeRoundingSoTheResultIsRoundedOnce() {
        // 53,640.874... x 0.5 = 26,820.44 -> 26,820. Rounding first would give 53,641 x 0.5 -> 26,821.
        assertThat(calculate("10", snapshot("F2", "0.5", PUMP))).isEqualTo(derived(26_820L));
    }

    @Test
    void kFactor_nullCountsAsOne() {
        assertThat(calculate("10", snapshot("F1", null, PUMP)))
                .isEqualTo(calculate("10", snapshot("F1", "1", PUMP)))
                .isEqualTo(derived(60_000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.5"})
    void kFactor_zeroOrNegative_isInvalid(String kFactor) {
        assertThat(calculate("10", snapshot("F1", kFactor, PUMP))).isEqualTo(notDerivable(Reason.INVALID_PARAMETER));
    }

    // ---- formula ----------------------------------------------------------------------------

    @Test
    void noSnapshot_isMissingFormula() {
        assertThat(calculate("10", null)).isEqualTo(notDerivable(Reason.MISSING_FORMULA));
    }

    @Test
    void noFormula_isMissingFormula() {
        assertThat(calculate("10", snapshot(null, null, PUMP))).isEqualTo(notDerivable(Reason.MISSING_FORMULA));
    }

    @Test
    void unknownFormulaCode_isMissingFormula() {
        assertThat(calculate("10", snapshot("F9", null, PUMP))).isEqualTo(notDerivable(Reason.MISSING_FORMULA));
    }

    @Test
    void noFormula_isNotDerivableEvenForZeroKilowattHours() {
        // A tenant with no formula has no ELM water quantity at all, so a first reading (0 kWh) shows
        // the missing configuration too, rather than a day of no supply.
        assertThat(calculate("0", snapshot(null, null, PUMP))).isEqualTo(notDerivable(Reason.MISSING_FORMULA));
    }

    @Test
    void zeroKilowattHours_isZeroLitres() {
        assertThat(calculate("0", snapshot("F1", null, PUMP))).isEqualTo(derived(0L));
    }

    @Test
    void constructor_withDuplicateFormulaCodes_throws() {
        assertThatThrownBy(() -> new ElmWaterQuantityCalculator(
                List.of(new ConsumptionRateFormula(), new ConsumptionRateFormula()),
                new PumpParameterAggregator(meterRegistry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("F1");
    }

    // ---- pumps ------------------------------------------------------------------------------

    @Test
    void noActivePump_isNoActivePump() {
        assertThat(calculate("10", new CalculationParameters(1, "F1", null, List.of())))
                .isEqualTo(notDerivable(Reason.NO_ACTIVE_PUMP));
    }

    @Test
    void aParameterTheFormulaNeedsIsMissing_isMissingParameter() {
        Pump noConsumption = pump(12).dischargeCapacityLpm("500").build();

        assertThat(calculate("10", snapshot("F1", null, noConsumption)))
                .isEqualTo(notDerivable(Reason.MISSING_PARAMETER));
    }

    @Test
    void anEfficiencyAboveOne_isInvalid() {
        Pump percentage = pump(12).pumpEfficiency("70").motorEfficiency("0.85").pumpHeadM("40").build();

        assertThat(calculate("10", snapshot("F3", null, percentage)))
                .isEqualTo(notDerivable(Reason.INVALID_PARAMETER));
    }

    @Test
    void aMotorPowerInAnUnknownUnit_isMissingForF2AndCounted() {
        Pump watts = pump(12).dischargeCapacityLpm("500").motorPower("5000", "W").unitsConsumedPerHour("5").build();

        assertThat(calculate("10", snapshot("F2", null, watts))).isEqualTo(notDerivable(Reason.MISSING_PARAMETER));
        assertThat(meterRegistry.counter("water_quantity.motor_power_unit.unknown").count()).isEqualTo(1.0);
    }

    @Test
    void aMotorPowerInAnUnknownUnit_isNotCountedForAFormulaThatDoesNotUseIt() {
        Pump watts = pump(12).dischargeCapacityLpm("500").motorPower("5000", "W").unitsConsumedPerHour("5").build();

        assertThat(calculate("10", snapshot("F1", null, watts))).isEqualTo(derived(60_000L));
        assertThat(meterRegistry.counter("water_quantity.motor_power_unit.unknown").count()).isZero();
    }

    @Test
    void theParametersAreAveragedOverThePumps() {
        // F2: (5.59275 kW + 5 kW) / 2 = 5.296375 kW; 10 x 500 x 60 / 5.296375 = 56,642.51 L
        Pump second = pump(13).dischargeCapacityLpm("500").motorPower("5", "kW").build();

        assertThat(calculate("10", snapshot("F2", null, PUMP, second))).isEqualTo(derived(56_643L));
    }

    // ---- kVAh register -----------------------------------------------------------------------

    @Test
    void kilovoltAmpereHours_areTurnedIntoKilowattHoursWithThePowerFactor() {
        Pump withPowerFactor = pump(12).dischargeCapacityLpm("500").unitsConsumedPerHour("5").powerFactor("0.9").build();

        // 10 kVAh x 0.9 = 9 kWh; 9 x 500 x 60 / 5 = 54,000 L
        assertThat(calculateKvah("10", snapshot("F1", null, withPowerFactor))).isEqualTo(derived(54_000L));
    }

    @Test
    void kilovoltAmpereHours_thePowerFactorAndKFactorAreAppliedBeforeRoundingSoTheResultIsRoundedOnce() {
        Pump withPowerFactor = pump(12).dischargeCapacityLpm("500").motorPower("7.5", "HP").powerFactor("0.85").build();

        // 10 kVAh x 0.85 = 8.5 kWh; 8.5 x 500 x 60 / 5.59275 = 45,594.74 L; x 0.9 = 41,035.27 L
        assertThat(calculateKvah("10", snapshot("F2", "0.9", withPowerFactor))).isEqualTo(derived(41_035L));
    }

    @Test
    void kilovoltAmpereHours_thePowerFactorIsAveragedOverThePumps() {
        Pump first = pump(12).dischargeCapacityLpm("500").unitsConsumedPerHour("5").powerFactor("0.9").build();
        Pump second = pump(13).dischargeCapacityLpm("500").unitsConsumedPerHour("5").powerFactor("0.8").build();

        // 10 kVAh x 0.85 = 8.5 kWh; 8.5 x 500 x 60 / 5 = 51,000 L
        assertThat(calculateKvah("10", snapshot("F1", null, first, second))).isEqualTo(derived(51_000L));
    }

    @Test
    void kilovoltAmpereHours_withNoPowerFactor_isMissingParameter() {
        assertThat(calculateKvah("10", snapshot("F1", null, PUMP))).isEqualTo(notDerivable(Reason.MISSING_PARAMETER));
    }

    @Test
    void kilovoltAmpereHours_withNoPowerFactor_isMissingParameterEvenForZero() {
        // As for a missing formula parameter: the scheme's data has to be fixed before any kVAh day counts.
        assertThat(calculateKvah("0", snapshot("F1", null, PUMP))).isEqualTo(notDerivable(Reason.MISSING_PARAMETER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.9", "1.01", "90"})
    void kilovoltAmpereHours_withAPowerFactorOutOfRange_isInvalid(String powerFactor) {
        Pump outOfRange = pump(12).dischargeCapacityLpm("500").unitsConsumedPerHour("5").powerFactor(powerFactor).build();

        assertThat(calculateKvah("10", snapshot("F1", null, outOfRange)))
                .isEqualTo(notDerivable(Reason.INVALID_PARAMETER));
    }

    @Test
    void kilovoltAmpereHours_withNoFormula_isMissingFormula() {
        Pump withPowerFactor = pump(12).dischargeCapacityLpm("500").unitsConsumedPerHour("5").powerFactor("0.9").build();

        assertThat(calculateKvah("10", snapshot(null, null, withPowerFactor)))
                .isEqualTo(notDerivable(Reason.MISSING_FORMULA));
    }

    @Test
    void kilowattHours_neverReadThePowerFactor() {
        Pump outOfRange = pump(12).dischargeCapacityLpm("500").unitsConsumedPerHour("5").powerFactor("90").build();

        assertThat(calculate("10", snapshot("F1", null, outOfRange))).isEqualTo(derived(60_000L));
    }

    // ---- range ------------------------------------------------------------------------------

    @Test
    void litresPastBigint_throwWithTheLitres() {
        assertThatThrownBy(() -> calculate("1e16", snapshot("F1", null, PUMP)))
                .isInstanceOfSatisfying(WaterVolumeOutOfRangeException.class, e -> {
                    assertThat(e.getValue()).isEqualByComparingTo("6e19");
                    assertThat(e.getUnit()).isEqualTo("L");
                });
    }

    /** A kWh day: the context leaves the register out, which means the standard one. */
    private WaterQuantityOutcome calculate(String kilowattHours, CalculationParameters parameters) {
        return calculator.calculate(context(kilowattHours, parameters).build());
    }

    private WaterQuantityOutcome calculateKvah(String kilovoltAmpereHours, CalculationParameters parameters) {
        return calculator.calculate(context(kilovoltAmpereHours, parameters)
                .register(MeterRegister.APPARENT_ENERGY)
                .build());
    }

    private static WaterQuantityContext.WaterQuantityContextBuilder context(String amount,
                                                                            CalculationParameters parameters) {
        return WaterQuantityContext.builder()
                .tenantId(1)
                .schemeId(11)
                .channel(ReadingChannel.ELM)
                .amount(new BigDecimal(amount))
                .parameters(parameters);
    }

    private static CalculationParameters snapshot(String formula, String kFactor, Pump... pumps) {
        return new CalculationParameters(1, formula, kFactor == null ? null : new BigDecimal(kFactor), List.of(pumps));
    }

    private static WaterQuantityOutcome derived(long litres) {
        return WaterQuantityOutcome.derived(litres);
    }

    private static WaterQuantityOutcome notDerivable(Reason reason) {
        return WaterQuantityOutcome.notDerivable(reason);
    }
}
