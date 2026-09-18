package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches to the right {@link MeterReadingExtractor} for a resolved {@link OcrProviderSettings}.
 *
 * <p>All extractor beans are collected at startup and indexed by {@link MeterReadingExtractor#providerId()}
 * (case-insensitively). An unknown / unconfigured provider falls back to the configured default
 * ({@code flowvision.default-provider}, default {@code flowvision}) so a mis-typed tenant config can never
 * drop a reading — it degrades to the built-in provider with a warning.
 */
@Component
@Slf4j
public class OcrProviderRegistry {

    private final Map<String, MeterReadingExtractor> extractorsById;
    private final String defaultProviderId;

    public OcrProviderRegistry(List<MeterReadingExtractor> extractors,
                               @Value("${flowvision.default-provider:" + OcrProviderSettings.DEFAULT_PROVIDER_ID + "}")
                               String defaultProviderId) {
        Map<String, MeterReadingExtractor> byId = new HashMap<>();
        for (MeterReadingExtractor extractor : extractors) {
            String id = normalize(extractor.providerId());
            if (id == null) {
                log.warn("Ignoring OCR provider with blank id: {}", extractor.getClass().getSimpleName());
                continue;
            }
            MeterReadingExtractor previous = byId.putIfAbsent(id, extractor);
            if (previous != null) {
                log.warn("Duplicate OCR provider id '{}' — keeping {}, ignoring {}",
                        id, previous.getClass().getSimpleName(), extractor.getClass().getSimpleName());
            }
        }
        this.extractorsById = Map.copyOf(byId);
        this.defaultProviderId = normalizeOrDefault(defaultProviderId);
        log.info("Registered OCR providers {} (default '{}')", this.extractorsById.keySet(), this.defaultProviderId);
    }

    /**
     * The extractor for {@code providerId}, or the default provider when {@code providerId} is null/blank
     * or not registered. Never returns {@code null}.
     */
    public MeterReadingExtractor get(String providerId) {
        String key = normalize(providerId);
        if (key != null) {
            MeterReadingExtractor extractor = extractorsById.get(key);
            if (extractor != null) {
                return extractor;
            }
            log.warn("Unknown OCR provider '{}' — falling back to default '{}'", key, defaultProviderId);
        }
        MeterReadingExtractor fallback = extractorsById.get(defaultProviderId);
        if (fallback == null) {
            throw new IllegalStateException(
                    "No OCR provider registered for default id '" + defaultProviderId + "'; registered: "
                            + extractorsById.keySet());
        }
        return fallback;
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
