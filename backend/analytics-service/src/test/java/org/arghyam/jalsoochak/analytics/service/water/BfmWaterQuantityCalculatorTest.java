package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.junit.jupiter.api.Test;

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
        assertThat(calculator.calculate(ctx(150, 100))).isEqualTo(50_000L);
    }

    @Test
    void calculate_whenDeltaIsNegative_returnsZero() {
        assertThat(calculator.calculate(ctx(95, 100))).isZero();
    }

    @Test
    void calculate_whenNoPreviousReadingExists_returnsZeroNotTheWholeMeterIndex() {
        // A null previous reading means "no baseline yet", not "the meter was at zero". Returning
        // 120,000 L here would be writing the entire cumulative index as one day's supply.
        assertThat(calculator.calculate(ctx(120, null))).isZero();
    }

    @Test
    void calculate_whenPreviousReadingIsZero_stillDerivesTheDelta() {
        // Distinct from the null case: a stored 0 is an actual reading, so the delta is derivable.
        // (The repository filters these out of the baseline lookup, so this is defensive.)
        assertThat(calculator.calculate(ctx(120, 0))).isEqualTo(120_000L);
    }

    @Test
    void calculate_whenCurrentReadingNull_returnsZero() {
        assertThat(calculator.calculate(ctx(null, 100))).isZero();
    }

    @Test
    void calculate_largeDeltaDoesNotOverflowInt() {
        // 3,000,000 m3 x 1000 = 3e9 L, past Integer.MAX_VALUE. Before the long return type this
        // silently wrapped negative; it must now come back exact.
        assertThat(calculator.calculate(ctx(3_000_000, 0))).isEqualTo(3_000_000_000L);
    }

    @Test
    void calculate_extremeReadingsDoNotWrapOnTheSubtraction() {
        // Subtracting as int would overflow here; the delta is computed as long before conversion.
        assertThat(calculator.calculate(ctx(Integer.MAX_VALUE, Integer.MIN_VALUE)))
                .isEqualTo(4_294_967_295_000L);
    }

    private static WaterQuantityContext ctx(Integer currentReading, Integer previousReading) {
        return WaterQuantityContext.builder()
                .tenantId(1)
                .schemeId(11)
                .currentReading(currentReading)
                .previousReading(previousReading)
                .channel(ReadingChannel.BFM.getCode())
                .build();
    }
}
