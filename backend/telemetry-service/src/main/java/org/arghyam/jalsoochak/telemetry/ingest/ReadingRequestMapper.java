package org.arghyam.jalsoochak.telemetry.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;

/**
 * Adapter that translates a state IT system's raw reading payload into the platform's canonical
 * reading request ({@link AssamReadingRequest}).
 *
 * <p>Onboarding a state whose IT system emits a different wire format is a matter of adding one
 * implementation of this interface (a Spring bean) declaring its {@link #format()} — the core
 * ingestion/processing pipeline is never touched. States that already conform to the canonical
 * contract use {@link CanonicalReadingRequestMapper} and need no code at all.
 */
public interface ReadingRequestMapper {

    /**
     * Discriminator that selects this mapper. Matched case-insensitively against the {@code {format}}
     * path variable of {@code POST /api/v1/telemetry/readings/formats/{format}}. Must be unique and
     * non-blank.
     */
    String format();

    /**
     * Translates a raw request body into the canonical {@link AssamReadingRequest}. Implementations
     * should not perform bean validation — the controller validates the returned object with the
     * shared constraints so every format is held to the same rules.
     *
     * @param rawBody parsed JSON of the incoming request (never {@code null}; may be a JSON null node)
     * @return the canonical request; never {@code null}
     */
    AssamReadingRequest map(JsonNode rawBody);
}
