package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;

/**
 * Strategy for extracting a water-meter reading from an image via an external AI/OCR provider.
 *
 * <p>One Spring bean per provider (FlowVision is the built-in implementation). The active provider is
 * chosen per tenant by {@link OcrProviderResolver} and dispatched to by {@link OcrProviderRegistry},
 * keyed on {@link #providerId()}. To add a new AI model for a state/tenant, implement this interface,
 * register it as a bean, and point that tenant's {@code ocr_provider} config key at its id — no changes
 * to the ingestion pipeline are required.
 *
 * <p>Results are normalised to {@link FlowVisionResult}, the internal reading contract shared by the
 * rollover resolver and reading persistence; each provider adapter maps its own response shape onto it.
 */
public interface MeterReadingExtractor {

    /** Stable, case-insensitive id matched against a tenant's configured {@code ocr_provider}. */
    String providerId();

    /**
     * Extracts a reading, absorbing failures: returns {@code null} (unreadable / infrastructure error)
     * or a rejected {@link FlowVisionResult} rather than throwing. Used by the non-resilient path.
     */
    FlowVisionResult extractReading(String imageUrl, OcrProviderSettings settings);

    /**
     * Extracts a reading but lets transient failures propagate so the resilience layer
     * ({@code FlowVisionReadingsRetryService}) can retry / trip the circuit breaker.
     */
    FlowVisionResult extractReadingOrThrow(String imageUrl, OcrProviderSettings settings);
}
