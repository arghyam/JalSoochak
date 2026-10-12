package org.arghyam.jalsoochak.message.channel.provider;

/**
 * What a provider reports happened to a message after it accepted it — the delivery half of a
 * notification's life, as opposed to {@code DispatchStatus}, which is our own half.
 *
 * <p>Provider-neutral: each adapter maps its own vocabulary onto these, keeping the provider's word
 * verbatim alongside. A word an adapter does not recognise maps to {@link #PENDING}, so a new
 * provider status can delay a row's settlement but never mis-settle it.</p>
 *
 * <p>States only move forward — {@code PENDING → DELIVERED → READ}, {@code PENDING → FAILED} — and
 * {@link #UNRESOLVED} and {@link #NOT_TRACKED} can still be settled by a late report. Mirrored by the
 * {@code chk_notification_delivery_status} constraint (V63).</p>
 */
public enum DeliveryState {

    /** Accepted by the provider, no final word yet. */
    PENDING,
    /** Reached the recipient's device or mailbox. */
    DELIVERED,
    /** Opened. Implies delivered. */
    READ,
    /** The provider gave up on it. */
    FAILED,
    /** Still pending when the status pull stopped looking. */
    UNRESOLVED,
    /** Accepted, but the provider gives no way to learn more: no message id, or no status API. */
    NOT_TRACKED,
    /** Never handed to the provider: skipped, suppressed, or failed before acceptance. */
    NOT_SENT;

    /** Whether a provider report can still change this state. */
    public boolean isOpen() {
        return this == PENDING || this == UNRESOLVED || this == NOT_TRACKED;
    }

    /** Whether the provider's final word is in. */
    public boolean isSettled() {
        return this == DELIVERED || this == READ || this == FAILED;
    }

    /**
     * Whether a report of {@code next} may replace this state. Open states take any settled one,
     * {@code DELIVERED} only {@code READ}; nothing replaces {@code READ}, {@code FAILED} or
     * {@code NOT_SENT}.
     */
    public boolean canAdvanceTo(DeliveryState next) {
        if (next == null) {
            return false;
        }
        if (isOpen()) {
            return next.isSettled();
        }
        return this == DELIVERED && next == READ;
    }
}
