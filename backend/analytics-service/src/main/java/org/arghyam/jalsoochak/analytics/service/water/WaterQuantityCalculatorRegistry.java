package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@link WaterQuantityCalculator} for a reading channel.
 *
 * <p>All {@link WaterQuantityCalculator} beans are registered by their
 * {@link WaterQuantityCalculator#channel() channel}. Legacy {@code null} and unknown channel codes
 * become {@link ReadingChannel#DEFAULT BFM} in {@link ReadingChannel#fromCode(Integer)}, before they
 * reach this class.
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
     * Returns the channel's calculator. A channel with none (ELM/PDU until their calculators exist,
     * IOT, MAN) returns {@link Optional#empty()} rather than falling back to BFM: callers must leave
     * the day alone rather than mis-derive it with another channel's calculator.
     */
    public Optional<WaterQuantityCalculator> resolve(ReadingChannel channel) {
        return Optional.ofNullable(byChannel.get(channel));
    }
}
