package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;

/**
 * Strategy for extracting a meter reading from an image via an external AI/OCR provider.
 *
 * <p>One Spring bean per provider. Each provider reads one kind of meter, so it declares the
 * {@link #channel()} it serves. The provider for a photo is chosen per tenant and channel by
 * {@link OcrProviderResolver} and dispatched to by {@link OcrProviderRegistry}, keyed on the channel and
 * {@link #providerId()}. To add a new AI model for a state/tenant, implement this interface, register it
 * as a bean, and point that tenant's provider config key for the channel at its id — no changes to the
 * ingestion pipeline are required.
 *
 * <p>Nothing injects a single extractor: every extraction goes through the registry, including for
 * tenants with no override, so a photo is never read by another channel's model. {@code null} settings
 * mean the tenant has no override, and every implementation must read them as "use your own globally
 * configured endpoint and credentials".
 *
 * <p>Results are normalised to {@link OcrReadingResult}, the internal reading contract shared by the
 * rollover resolver and reading persistence; each provider adapter maps its own response shape onto it.
 */
public interface MeterReadingExtractor {

    /** Stable, case-insensitive id matched against a tenant's configured provider for {@link #channel()}. */
    String providerId();

    /** The reading channel whose meter photos this provider reads. It is never chosen for another. */
    ReadingChannel channel();

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
