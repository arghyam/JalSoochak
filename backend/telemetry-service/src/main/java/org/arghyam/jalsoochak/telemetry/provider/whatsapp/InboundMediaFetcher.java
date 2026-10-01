package org.arghyam.jalsoochak.telemetry.provider.whatsapp;

import org.springframework.http.ResponseEntity;

/**
 * Downloads media that arrived on an inbound WhatsApp message and that the provider holds under an
 * id of its own.
 *
 * <p>Only the provider-specific part sits behind this port: where a media id resolves to, and how
 * the request authenticates. Retrying, and deciding whether a response is a usable image, stay with
 * the caller, which applies the same policy to media fetched from a URL.
 */
public interface InboundMediaFetcher {

    /**
     * Issues a single request for the media held under {@code mediaId}.
     *
     * @return the provider's response, whatever its status — the caller judges it
     * @throws IllegalStateException if {@code mediaId} cannot be addressed safely; retrying cannot help
     * @throws org.springframework.web.client.RestClientException on a transport or HTTP error, which
     *         the caller may retry
     */
    ResponseEntity<byte[]> fetch(String mediaId);
}
