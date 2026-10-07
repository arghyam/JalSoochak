package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters.Pump;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Reason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.arghyam.jalsoochak.analytics.service.water.PumpFixture.pump;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One run's litres; the day's sum is {@code WaterQuantityRecalculationServiceTest}'s concern. */
class PduWaterQuantityCalculatorTest {

    private static final Pump PUMP = pump(12).dischargeCapacityLpm("500").build();

    private final PduWaterQuantityCalculator calculator =
            new PduWaterQuantityCalculator(new PumpParameterAggregator(new SimpleMeterRegistry()));

    @Test
    void channel_isPdu() {
        assertThat(calculator.channel()).isEqualTo(ReadingChannel.PDU);
    }

    @Test
    void workedExample_minutesTimesTheDischargeRate() {
        assertThat(calculate("90", snapshot(null, PUMP))).isEqualTo(WaterQuantityOutcome.derived(45_000L));
    }

    @Test
    void kFactor_isIgnored() {
        // Telemetry never sends one for PDU; one that arrives anyway changes nothing.
        assertThat(calculate("90", snapshot("0.9", PUMP))).isEqualTo(WaterQuantityOutcome.derived(45_000L));
    }

    @Test
    void decimalMinutes_areRoundedOnceToWholeLitres() {
        // 1.5 min x 333.3 LPM = 499.95 L
        Pump pump = pump(12).dischargeCapacityLpm("333.3").build();

        assertThat(calculate("1.5", snapshot(null, pump))).isEqualTo(WaterQuantityOutcome.derived(500L));
    }

    @Test
    void thePumpsRunTogether_soTheirDischargeRatesAddUp() {
        // 90 min x (500 + 300) LPM
        Pump second = pump(13).dischargeCapacityLpm("300").build();

        assertThat(calculate("90", snapshot(null, PUMP, second))).isEqualTo(WaterQuantityOutcome.derived(72_000L));
    }

    @Test
    void aPumpWithNoDischargeRateCountsAtTheOtherPumpsAverage() {
        // 90 min x (500 + 500) LPM
        Pump noRate = pump(13).pumpHeadM("40").build();

        assertThat(calculate("90", snapshot(null, PUMP, noRate))).isEqualTo(WaterQuantityOutcome.derived(90_000L));
    }

    @Test
    void noSnapshotOrNoPump_isNoActivePump() {
        assertThat(calculate("90", null)).isEqualTo(WaterQuantityOutcome.notDerivable(Reason.NO_ACTIVE_PUMP));
        assertThat(calculate("90", snapshot(null)))
                .isEqualTo(WaterQuantityOutcome.notDerivable(Reason.NO_ACTIVE_PUMP));
    }

    @Test
    void noDischargeRate_isMissingParameter() {
        assertThat(calculate("90", snapshot(null, pump(12).pumpHeadM("40").build())))
                .isEqualTo(WaterQuantityOutcome.notDerivable(Reason.MISSING_PARAMETER));
    }

    @Test
    void aZeroDischargeRate_isInvalidRatherThanZeroLitres() {
        assertThat(calculate("90", snapshot(null, pump(12).dischargeCapacityLpm("0").build())))
                .isEqualTo(WaterQuantityOutcome.notDerivable(Reason.INVALID_PARAMETER));
    }

    @Test
    void litresPastBigint_throwWithTheLitres() {
        assertThatThrownBy(() -> calculate("1e17", snapshot(null, PUMP)))
                .isInstanceOfSatisfying(WaterVolumeOutOfRangeException.class, e -> {
                    assertThat(e.getValue()).isEqualByComparingTo("5e19");
                    assertThat(e.getUnit()).isEqualTo("L");
                });
    }

    private WaterQuantityOutcome calculate(String minutes, CalculationParameters parameters) {
        return calculator.calculate(WaterQuantityContext.builder()
                .tenantId(1)
                .schemeId(11)
                .channel(ReadingChannel.PDU)
                .amount(new BigDecimal(minutes))
                .parameters(parameters)
                .build());
    }

    private static CalculationParameters snapshot(String kFactor, Pump... pumps) {
        return new CalculationParameters(1, null, kFactor == null ? null : new BigDecimal(kFactor), List.of(pumps));
    }
}
