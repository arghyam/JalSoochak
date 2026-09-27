package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BfmWaterQuantityCalculatorTest {

    private final BfmWaterQuantityCalculator calculator = new BfmWaterQuantityCalculator();

    @Test
    void channel_isBfm() {
        assertThat(calculator.channel()).isEqualTo(ReadingChannel.BFM);
    }

    @Test
    void calculate_returnsDeltaOverPreviousReadingConvertedToLitres() {
        // 50 m3 supplied -> 50,000 L stored.
        assertThat(calculator.calculate(ctx("150", "100"))).isEqualTo(50_000L);
    }

    @Test
    void calculate_whenDeltaIsNegative_returnsZero() {
        assertThat(calculator.calculate(ctx("95", "100"))).isZero();
    }

    @Test
    void calculate_whenNoPreviousReadingExists_returnsZeroNotTheWholeMeterIndex() {
        // A null previous reading means "no baseline yet", not "the meter was at zero". Returning
        // 120,000 L here would be writing the entire cumulative index as one day's supply.
        assertThat(calculator.calculate(ctx("120", null))).isZero();
    }

    @Test
    void calculate_whenPreviousReadingIsZero_stillDerivesTheDelta() {
        // Distinct from the null case: a stored 0 is an actual reading, so the delta is derivable.
        // (The repository filters these out of the baseline lookup, so this is defensive.)
        assertThat(calculator.calculate(ctx("120", "0"))).isEqualTo(120_000L);
    }

    @Test
    void calculate_whenCurrentReadingNull_returnsZero() {
        assertThat(calculator.calculate(ctx(null, "100"))).isZero();
    }

    @Test
    void calculate_largeDeltaDoesNotOverflowInt() {
        // 3,000,000 m3 x 1000 = 3e9 L, past Integer.MAX_VALUE. Before the long return type this
        // silently wrapped negative; it must now come back exact.
        assertThat(calculator.calculate(ctx("3000000", "0"))).isEqualTo(3_000_000_000L);
    }

    @Test
    void calculate_keepsTheMetersDecimalDigit() {
        // The whole point of the NUMERIC readings: 12.3 m3 is 12,300 L, not 12,000 L.
        assertThat(calculator.calculate(ctx("1247.8", "1235.5"))).isEqualTo(12_300L);
    }

    @Test
    void calculate_subtractsBeforeRounding_notAfter() {
        // Rounding each reading first gives 1236 - 1235 = 1 m3 = 1000 L. Subtracting first gives the
        // true 0.4 m3 = 400 L. The 600 L gap is the per-day error this change removes; it was invisible
        // while the column held cubic metres and became visible once it held litres.
        assertThat(calculator.calculate(ctx("1235.9", "1235.5"))).isEqualTo(400L);
    }

    @Test
    void calculate_subMetreDeltaIsNoLongerLostEntirely() {
        // Two readings inside the same whole m3 used to round to an identical integer and derive 0.
        assertThat(calculator.calculate(ctx("1235.7", "1235.2"))).isEqualTo(500L);
    }

    @Test
    void calculate_readingScaleDoesNotAffectTheResult() {
        // NUMERIC is unconstrained, so the same reading can arrive as 100, 100.0 or 100.00.
        assertThat(calculator.calculate(ctx("150.0", "100")))
                .isEqualTo(calculator.calculate(ctx("150", "100.00")))
                .isEqualTo(50_000L);
    }

    @Test
    void calculate_deltaSmallerThanOneLitreRoundsRatherThanThrowing() {
        // Far below meter resolution, but the arithmetic must still terminate in a whole-litre value.
        assertThat(calculator.calculate(ctx("100.00051", "100"))).isEqualTo(1L);
    }

    @Test
    void calculate_extremeReadingsDoNotWrapOnTheSubtraction() {
        // Readings are unbounded NUMERIC, so the subtraction has to happen at arbitrary precision —
        // as int or long it would wrap here.
        assertThat(calculator.calculate(ctx(String.valueOf(Integer.MAX_VALUE),
                String.valueOf(Integer.MIN_VALUE))))
                .isEqualTo(4_294_967_295_000L);
    }

    private static WaterQuantityContext ctx(String currentReading, String previousReading) {
        return WaterQuantityContext.builder()
                .tenantId(1)
                .schemeId(11)
                .currentReading(currentReading == null ? null : new BigDecimal(currentReading))
                .previousReading(previousReading == null ? null : new BigDecimal(previousReading))
                .channel(ReadingChannel.BFM.getCode())
                .build();
    }
}
