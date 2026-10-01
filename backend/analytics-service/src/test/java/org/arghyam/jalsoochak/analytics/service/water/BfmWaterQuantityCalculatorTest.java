package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Derived;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The amount is already the day's m&sup3; (the METER_INDEX rule works out the difference; see
 * {@code WaterQuantityRecalculationServiceTest}), so BFM is only the conversion to litres.
 */
class BfmWaterQuantityCalculatorTest {

    private final BfmWaterQuantityCalculator calculator = new BfmWaterQuantityCalculator();

    @Test
    void channel_isBfm() {
        assertThat(calculator.channel()).isEqualTo(ReadingChannel.BFM);
    }

    @Test
    void calculate_convertsTheDaysCubicMetresToLitres() {
        // 50 m3 supplied -> 50,000 L stored.
        assertThat(litres("50")).isEqualTo(50_000L);
    }

    @Test
    void calculate_zeroAmountIsZeroLitres() {
        assertThat(litres("0")).isZero();
    }

    @Test
    void calculate_largeAmountDoesNotOverflowInt() {
        // 3,000,000 m3 x 1000 = 3e9 L, past Integer.MAX_VALUE.
        assertThat(litres("3000000")).isEqualTo(3_000_000_000L);
    }

    @Test
    void calculate_keepsTheMetersDecimalDigit() {
        // 12.3 m3 is 12,300 L, not 12,000 L.
        assertThat(litres("12.3")).isEqualTo(12_300L);
    }

    @Test
    void calculate_amountSmallerThanOneLitreRoundsHalfUp() {
        assertThat(litres("0.00051")).isEqualTo(1L);
        assertThat(litres("0.0005")).isEqualTo(1L);
        assertThat(litres("0.00049")).isZero();
    }

    @Test
    void calculate_amountScaleDoesNotAffectTheResult() {
        assertThat(litres("50.00")).isEqualTo(litres("50")).isEqualTo(50_000L);
    }

    @Test
    void calculate_amountPastBigintThrowsWithTheCubicMetres() {
        // 1e16 m3 x 1000 is past BIGINT: signalled, never clamped.
        assertThatThrownBy(() -> litres("1e16"))
                .isInstanceOfSatisfying(WaterVolumeOutOfRangeException.class, e -> {
                    assertThat(e.getValue()).isEqualByComparingTo("1e16");
                    assertThat(e.getUnit()).isEqualTo("m3");
                });
    }

    private long litres(String amount) {
        WaterQuantityOutcome outcome = calculator.calculate(WaterQuantityContext.builder()
                .tenantId(1)
                .schemeId(11)
                .channel(ReadingChannel.BFM)
                .amount(new BigDecimal(amount))
                .build());
        assertThat(outcome).isInstanceOf(Derived.class);
        return ((Derived) outcome).litres();
    }
}
