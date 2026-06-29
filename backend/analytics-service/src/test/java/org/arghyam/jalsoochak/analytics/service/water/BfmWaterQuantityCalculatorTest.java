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
    void calculate_returnsDeltaOverPreviousReading() {
        assertThat(calculator.calculate(150, 100)).isEqualTo(50);
    }

    @Test
    void calculate_whenDeltaIsNegative_returnsZero() {
        assertThat(calculator.calculate(95, 100)).isZero();
    }

    @Test
    void calculate_whenPreviousReadingNull_treatsItAsZero() {
        assertThat(calculator.calculate(120, null)).isEqualTo(120);
    }

    @Test
    void calculate_whenCurrentReadingNull_returnsZero() {
        assertThat(calculator.calculate(null, 100)).isZero();
    }
}
