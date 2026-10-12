package org.arghyam.jalsoochak.message.dto;

/**
 * Our normalised reading of the WhatsApp provider's {@code bspStatus} — what actually happened to a
 * message once the provider and Meta were done with it.
 *
 * <p>Vendor-neutral: each {@code WhatsAppDeliveryStatusReader} adapter maps its own status words onto
 * these, and keeps the raw word alongside on {@link WhatsAppMessageStatus#bspStatus()} so nothing is
 * lost. A word an adapter does not recognise maps to {@link #UNKNOWN_STATUS}, never to an exception: a
 * provider may add statuses, and a reconciliation pass must not die on one it has never seen.</p>
 *
 * <p><strong>Note the {@code SENT} collision.</strong> A provider's {@code SENT} usually means <em>Meta
 * accepted the message, not yet delivered</em>; our own {@code result=SENT} log token means <em>the
 * provider accepted our API call</em>. They are different facts about different hops, so a provider's
 * {@code SENT} maps to {@link #PENDING} and is never re-emitted as a bare {@code result=SENT}.</p>
 */
public enum WhatsAppDeliveryOutcome {

    /** Reached the handset. */
    DELIVERED(true),

    /** Opened, seen or played by the recipient — implies delivered. */
    READ(true),

    /** The provider or Meta rejected it, or the contact opted out. The reason travels with the message. */
    DELIVERY_FAILED(true),

    /** Still in flight: queued at the provider, or accepted by Meta but not yet delivered. */
    PENDING(false),

    /** A status this build does not know. Never thrown on — the raw value is logged. */
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
}
