package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaterQuantityRangeReporterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 2);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void reportIfImplausible_countsOnlyAboveTheThresholdAndTagsTheSource() {
        WaterQuantityRangeReporter reporter = new WaterQuantityRangeReporter(meterRegistry, 100L);

        reporter.reportIfImplausible(100_000L, 1, 11, DAY, "reading");
        reporter.reportIfImplausible(100_001L, 1, 11, DAY, "reading");

        assertThat(meterRegistry.counter("water_quantity.implausible", "source", "reading").count())
                .isEqualTo(1.0);
    }

    @Test
    void reportUnstorable_countsWithTheSource() {
        WaterQuantityRangeReporter reporter = new WaterQuantityRangeReporter(meterRegistry, 100L);

        reporter.reportUnstorable(new WaterVolumeOutOfRangeException(new BigDecimal("1e16"), "m3"),
                1, 11, DAY, "correction");

        assertThat(meterRegistry.counter("water_quantity.unstorable", "source", "correction").count())
                .isEqualTo(1.0);
    }

    @Test
    void constructor_rejectsANegativeThreshold() {
        assertThatThrownBy(() -> new WaterQuantityRangeReporter(meterRegistry, -1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("implausible-daily-cubic-metres");
    }

    @Test
    void constructor_rejectsAThresholdWhoseLitresOverflow() {
        // Caught at startup, not as an ArithmeticException on the Kafka consumer thread.
        assertThatThrownBy(() -> new WaterQuantityRangeReporter(meterRegistry, Long.MAX_VALUE / 1000 + 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
