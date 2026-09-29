package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the {@link MeterReadingExtractor} that reads a photo for a channel.
 *
 * <p>All extractor beans are collected at startup and indexed by {@link MeterReadingExtractor#channel()}
 * and {@link MeterReadingExtractor#providerId()} (case-insensitively), so a provider is only ever chosen
 * for the channel whose meters it reads. A tenant's provider that isn't registered for the channel falls
 * back to the channel's default provider with a warning, so a mis-typed tenant config can never drop a
 * reading. BFM's default is {@code ocr.default-provider} (default
 * {@link OcrProviderSettings#DEFAULT_PROVIDER_ID}); other channels have none until an extractor is
 * written for them, so their photos can't be read yet.
 */
@Component
@Slf4j
public class OcrProviderRegistry {

    private final Map<ReadingChannel, Map<String, MeterReadingExtractor>> extractorsByChannel;
    private final String bfmDefaultProviderId;

    public OcrProviderRegistry(List<MeterReadingExtractor> extractors,
                               @Value("${ocr.default-provider:" + OcrProviderSettings.DEFAULT_PROVIDER_ID + "}")
                               String bfmDefaultProviderId) {
        Map<ReadingChannel, Map<String, MeterReadingExtractor>> byChannel = new EnumMap<>(ReadingChannel.class);
        for (MeterReadingExtractor extractor : extractors) {
            String id = normalize(extractor.providerId());
            ReadingChannel channel = extractor.channel();
            if (id == null) {
                log.warn("Ignoring OCR provider with blank id: {}", extractor.getClass().getSimpleName());
                continue;
            }
            if (channel == null || !channel.supportsImageReading()) {
                log.warn("Ignoring OCR provider '{}': channel {} doesn't read meter photos", id, channel);
                continue;
            }
            MeterReadingExtractor previous = byChannel
                    .computeIfAbsent(channel, ignored -> new HashMap<>())
                    .putIfAbsent(id, extractor);
            if (previous != null) {
                log.warn("Duplicate OCR provider id '{}' for channel {} — keeping {}, ignoring {}",
                        id, channel, previous.getClass().getSimpleName(), extractor.getClass().getSimpleName());
            }
        }
        Map<ReadingChannel, Map<String, MeterReadingExtractor>> frozen = new EnumMap<>(ReadingChannel.class);
        byChannel.forEach((channel, byId) -> frozen.put(channel, Map.copyOf(byId)));
        this.extractorsByChannel = frozen;
        this.bfmDefaultProviderId = normalizeOrDefault(bfmDefaultProviderId);
        log.info("Registered OCR providers {} (BFM default '{}')", registeredIds(), this.bfmDefaultProviderId);
    }

    /**
     * The extractor that reads {@code channel}'s photos: {@code providerId} when it is registered for the
     * channel, otherwise the channel's default provider. Empty when the channel has no default provider,
     * which means its photos can't be read.
     *
     * @throws IllegalStateException when BFM's configured default provider isn't registered
     */
    public Optional<MeterReadingExtractor> get(ReadingChannel channel, String providerId) {
        Map<String, MeterReadingExtractor> forChannel = extractorsByChannel.getOrDefault(channel, Map.of());
        String key = normalize(providerId);
        if (key != null) {
            MeterReadingExtractor extractor = forChannel.get(key);
            if (extractor != null) {
                return Optional.of(extractor);
            }
        }
        Optional<String> defaultId = defaultProviderId(channel);
        if (key != null) {
            log.warn("OCR provider '{}' is not registered for channel {} — falling back to {}",
                    key, channel, defaultId.map(id -> "default '" + id + "'").orElse("none"));
        }
        if (defaultId.isEmpty()) {
            return Optional.empty();
        }
        MeterReadingExtractor fallback = forChannel.get(defaultId.get());
        if (fallback == null) {
            throw new IllegalStateException(
                    "No OCR provider registered for channel " + channel + " default id '" + defaultId.get()
                            + "'; registered: " + registeredIds());
        }
        return Optional.of(fallback);
    }

    /** Provider ids by channel, for logs, e.g. {@code {BFM=[a, b], ELM=[c]}}. */
    private Map<ReadingChannel, List<String>> registeredIds() {
        Map<ReadingChannel, List<String>> ids = new EnumMap<>(ReadingChannel.class);
        extractorsByChannel.forEach((channel, byId) -> ids.put(channel, byId.keySet().stream().sorted().toList()));
        return ids;
    }

    private Optional<String> defaultProviderId(ReadingChannel channel) {
        return channel == ReadingChannel.BFM ? Optional.of(bfmDefaultProviderId) : Optional.empty();
    }

    private static String normalize(String providerId) {
        if (providerId == null) {
            return null;
        }
        String trimmed = providerId.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeOrDefault(String providerId) {
        String normalized = normalize(providerId);
        return normalized == null ? OcrProviderSettings.DEFAULT_PROVIDER_ID : normalized;
    }
}
