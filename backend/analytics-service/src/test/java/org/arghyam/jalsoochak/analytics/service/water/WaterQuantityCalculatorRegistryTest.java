package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        public WaterQuantityOutcome calculate(WaterQuantityContext context) {
            return WaterQuantityOutcome.notDerivable(WaterQuantityOutcome.Reason.MISSING_FORMULA);
        }
    }

    @Test
    void resolve_legacyNullChannelCode_reachesTheBfmCalculator() {
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm));

        assertThat(registry.resolve(ReadingChannel.fromCode(null))).contains(bfm);
    }

    @Test
    void resolve_everyExplicitNonDefaultChannelWithoutCalculator_returnsEmpty() {
        // Only BFM registered: every explicitly non-default channel (ELM/PDU/IOT/MAN) must
        // resolve to empty so callers skip rather than silently mis-deriving with the BFM calculator.
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm));

        for (ReadingChannel channel : ReadingChannel.values()) {
            if (channel == ReadingChannel.DEFAULT) {
                continue;
            }
            assertThat(registry.resolve(channel))
                    .as("channel %s has no calculator and must not fall back to BFM", channel)
                    .isEmpty();
        }
    }

    @Test
    void resolve_registeredChannelCalculator_returnsThatCalculator() {
        ElmCalculator elm = new ElmCalculator();
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm, elm));

        assertThat(registry.resolve(ReadingChannel.ELM)).contains(elm);
        assertThat(registry.resolve(ReadingChannel.BFM)).contains(bfm);
    }

    @Test
    void resolve_withTheProductionCalculators_coversBfmElmAndPduOnly() {
        PumpParameterAggregator aggregator = new PumpParameterAggregator(new SimpleMeterRegistry());
        ElmWaterQuantityCalculator elm = new ElmWaterQuantityCalculator(
                List.of(new ConsumptionRateFormula(), new MotorPowerFormula(), new HydraulicEnergyFormula()),
                aggregator);
        PduWaterQuantityCalculator pdu = new PduWaterQuantityCalculator(aggregator);
        WaterQuantityCalculatorRegistry registry = new WaterQuantityCalculatorRegistry(List.of(bfm, elm, pdu));

        assertThat(registry.resolve(ReadingChannel.BFM)).contains(bfm);
        assertThat(registry.resolve(ReadingChannel.ELM)).contains(elm);
        assertThat(registry.resolve(ReadingChannel.PDU)).contains(pdu);
        assertThat(registry.resolve(ReadingChannel.IOT)).isEmpty();
        assertThat(registry.resolve(ReadingChannel.MAN)).isEmpty();
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
