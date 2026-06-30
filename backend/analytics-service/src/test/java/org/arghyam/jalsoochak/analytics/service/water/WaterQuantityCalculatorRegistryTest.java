package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaterQuantityCalculatorRegistryTest {

    private final BfmWaterQuantityCalculator bfm = new BfmWaterQuantityCalculator();

    /** A stand-in calculator for a non-default channel, proving the registry is pluggable. */
    private static final class ElmCalculator implements WaterQuantityCalculator {
        @Override
        public ReadingChannel channel() {
            return ReadingChannel.ELM;
        }

        @Override
        public int calculate(Integer currentReading, Integer previousReading) {
            // Distinct from BFM: the reading itself is the day's quantity.
            return currentReading != null ? Math.max(0, currentReading) : 0;
        }
    }

    @Test
    void resolve_nullChannel_returnsDefaultBfmCalculator() {
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm));

        assertThat(registry.resolve(null)).isSameAs(bfm);
    }

    @Test
    void resolve_unknownChannelCode_returnsDefaultBfmCalculator() {
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm));

        assertThat(registry.resolve(999)).isSameAs(bfm);
    }

    @Test
    void resolve_channelWithoutDedicatedCalculator_fallsBackToBfm() {
        // ELM code provided but no ELM calculator registered -> BFM default.
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm));

        assertThat(registry.resolve(ReadingChannel.ELM.getCode())).isSameAs(bfm);
    }

    @Test
    void resolve_registeredChannelCalculator_returnsThatCalculator() {
        ElmCalculator elm = new ElmCalculator();
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm, elm));

        assertThat(registry.resolve(ReadingChannel.ELM.getCode())).isSameAs(elm);
        assertThat(registry.resolve(ReadingChannel.BFM.getCode())).isSameAs(bfm);
    }

    @Test
    void constructor_withoutDefaultCalculator_throws() {
        assertThatThrownBy(() -> new WaterQuantityCalculatorRegistry(List.of(new ElmCalculator())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BFM");
    }

    @Test
    void constructor_withDuplicateChannelCalculators_throws() {
        assertThatThrownBy(() ->
                new WaterQuantityCalculatorRegistry(List.of(bfm, new BfmWaterQuantityCalculator())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }
}
