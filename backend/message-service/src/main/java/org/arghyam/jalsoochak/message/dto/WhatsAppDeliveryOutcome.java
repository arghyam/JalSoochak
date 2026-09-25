package org.arghyam.jalsoochak.message.dto;

import java.util.Locale;

/**
 * Our normalised reading of the WhatsApp provider's {@code bspStatus} — what actually happened to a
 * message once Gupshup and Meta were done with it.
 *
 * <p>The provider's own {@code MessageStatusEnum} is
 * {@code CONTACT_OPT_OUT, DELETED, DELIVERED, ENQUEUED, ERROR, PLAYED, REACHED, READ, RECEIVED, SEEN,
 * SENT} (confirmed by introspection). This enum collapses it to the five outcomes worth counting, and
 * keeps the raw value alongside so nothing is lost.</p>
 *
 * <p><strong>Note the {@code SENT} collision.</strong> The provider's {@code SENT} means <em>Meta
 * accepted the message, not yet delivered</em>; our own {@code result=SENT} log token means <em>the
 * provider accepted our API call</em>. They are different facts about different hops, so the
 * provider's {@code SENT} maps to {@link #PENDING} here and is never re-emitted as a bare
 * {@code result=SENT}.</p>
 */
public enum WhatsAppDeliveryOutcome {

    /** Reached the handset. */
    DELIVERED(true),

    /**
     * Opened by the recipient — implies delivered. Covers the provider's {@code READ}, {@code SEEN},
     * {@code PLAYED}.
     */
    READ(true),

    /** Gupshup or Meta rejected it. The reason lives in the message's {@code errors} payload. */
    DELIVERY_FAILED(true),

    /** Still in flight: queued at the provider, or accepted by Meta but not yet delivered. */
    PENDING(false),

    /** A {@code bspStatus} this build does not know. Never thrown on — the raw value is logged. */
    UNKNOWN_STATUS(false),

    /** Not an outbound delivery we care about: an inbound message, or one deleted at the provider. */
    IGNORED(false);

    private final boolean terminal;

    WhatsAppDeliveryOutcome(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when the status will not change again, so re-checking the message is pointless. */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Maps one provider {@code bspStatus} onto our vocabulary.
     *
     * <p>An unrecognised value yields {@link #UNKNOWN_STATUS} rather than an exception: the provider may add
     * enum members, and a reconciliation pass must not die on a status it has never seen.</p>
     */
    public static WhatsAppDeliveryOutcome fromBspStatus(String bspStatus) {
        if (bspStatus == null || bspStatus.isBlank()) {
            return UNKNOWN_STATUS;
        }
        return switch (bspStatus.trim().toUpperCase(Locale.ROOT)) {
            case "DELIVERED" -> DELIVERED;
            case "READ", "SEEN", "PLAYED" -> READ;
            case "ERROR", "CONTACT_OPT_OUT" -> DELIVERY_FAILED;
            // ENQUEUED = still at the provider; SENT = Meta has it but has not delivered it;
            // REACHED = broadcast-level, not expected on a direct HSM.
            case "ENQUEUED", "SENT", "REACHED" -> PENDING;
            // RECEIVED is inbound (contact → us); DELETED was removed at the provider.
            case "RECEIVED", "DELETED" -> IGNORED;
            default -> UNKNOWN_STATUS;
        };
    }
}
