package org.arghyam.jalsoochak.message.channel;

/**
 * Where a Glific send failed, so a {@code result=FAILED_DELIVERY} log line says which half of the
 * handoff broke instead of collapsing every cause into one token.
 *
 * <p>The distinction is operational, not cosmetic. The 20 Aug 2026 incident
 * ({@code (#131053) Media upload error}) failed at {@link #MEDIA_REGISTER} — Meta could not fetch the
 * MinIO URL — which is a completely different fix from a template or receiver problem at
 * {@link #SEND}. Both looked identical in the logs at the time.</p>
 */
public enum GlificSendStage {

    /**
     * {@code createMessageMedia} rejected the PDF. DOCUMENT mode only: Glific validates the media URL
     * with the BSP before accepting it, so an unreachable or geo-blocked MinIO surfaces here.
     */
    MEDIA_REGISTER,

    /** {@code sendHsmMessage} / {@code createAndSendMessage} rejected the send itself. */
    SEND,

    /**
     * The 30 s {@code block()} in {@link GlificGraphQLClient} expired.
     *
     * <p>Its own token because it is the one failure a retry makes <em>worse</em>: Glific may already
     * have created and sent the message, so re-driving the event delivers a duplicate rather than
     * repairing anything.</p>
     */
    TIMEOUT,

    /**
     * The send never reached Glific because our own configuration or inputs were wrong — a missing
     * template id, an unresolved contact id, or a MinIO URL that does not sit under the prefix the
     * approved template froze. Retrying cannot help until configuration changes.
     */
    CONFIG
}
