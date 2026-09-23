package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;

/**
 * Strategy for extracting a water-meter reading from an image via an external AI/OCR provider.
 *
 * <p>One Spring bean per provider (FlowVision is the built-in implementation). The active provider is
 * chosen per tenant by {@link OcrProviderResolver} and dispatched to by {@link OcrProviderRegistry},
 * keyed on {@link #providerId()}. To add a new AI model for a state/tenant, implement this interface,
 * register it as a bean, and point that tenant's {@code ocr_provider} config key at its id — no changes
 * to the ingestion pipeline are required.
 *
 * <p>Exactly one implementation is marked {@code @Primary}: the built-in provider that serves tenants with
 * no {@code ocr_*} override. Callers inject it by this type and pass {@code null} settings, which every
 * implementation must read as "use your own globally configured endpoint and credentials".
 *
 * <p>Results are normalised to {@link OcrReadingResult}, the internal reading contract shared by the
 * rollover resolver and reading persistence; each provider adapter maps its own response shape onto it.
 */
public interface MeterReadingExtractor {

    /** Stable, case-insensitive id matched against a tenant's configured {@code ocr_provider}. */
    String providerId();

    /**
     * Extracts a reading, absorbing failures: returns {@code null} (unreadable / infrastructure error)
     * or a rejected {@link OcrReadingResult} rather than throwing. Used by the non-resilient path.
     * {@code null} settings mean the provider's own global defaults.
     */
    OcrReadingResult extractReading(String imageUrl, OcrProviderSettings settings);

    /**
     * Extracts a reading but lets transient failures propagate so the resilience layer
     * ({@code OcrReadingsRetryService}) can retry / trip the circuit breaker. {@code null} settings mean
     * the provider's own global defaults.
     */
    OcrReadingResult extractReadingOrThrow(String imageUrl, OcrProviderSettings settings);
}
