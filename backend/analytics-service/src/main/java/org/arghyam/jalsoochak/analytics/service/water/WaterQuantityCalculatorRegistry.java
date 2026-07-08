package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@link WaterQuantityCalculator} for a reading's channel code.
 *
 * <p>All {@link WaterQuantityCalculator} beans are registered by their
 * {@link WaterQuantityCalculator#channel() channel}. Unknown or {@code null}
 * channel codes fall back to the {@link ReadingChannel#DEFAULT BFM} calculator,
 * which keeps legacy events (channel == null) behaving as before.
 */
@Component
public class WaterQuantityCalculatorRegistry {

    private final Map<ReadingChannel, WaterQuantityCalculator> byChannel = new EnumMap<>(ReadingChannel.class);

    public WaterQuantityCalculatorRegistry(List<WaterQuantityCalculator> calculators) {
        for (WaterQuantityCalculator calculator : calculators) {
            WaterQuantityCalculator existing = byChannel.putIfAbsent(calculator.channel(), calculator);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate WaterQuantityCalculator registered for channel " + calculator.channel()
                                + ": " + existing.getClass().getName() + " and " + calculator.getClass().getName());
            }
        }
        if (!byChannel.containsKey(ReadingChannel.DEFAULT)) {
            throw new IllegalStateException(
                    "No default (" + ReadingChannel.DEFAULT + ") WaterQuantityCalculator registered");
        }
    }

    /**
     * Returns the calculator for the given numeric channel code, if one can be
     * safely applied.
     *
     * <p>{@code null} and unknown codes resolve to the default
     * ({@link ReadingChannel#DEFAULT BFM}) calculator via {@link ReadingChannel#fromCode(Integer)},
     * preserving legacy behaviour. An <em>explicitly</em> non-default channel
     * (e.g. ELM/PDU) with no registered calculator returns {@link Optional#empty()}
     * rather than silently falling back to BFM — callers must skip rather than
     * mis-derive the reading with the wrong calculator.
     */
    public Optional<WaterQuantityCalculator> resolve(Integer channelCode) {
        ReadingChannel channel = ReadingChannel.fromCode(channelCode);
        return Optional.ofNullable(byChannel.get(channel));
    }
}
