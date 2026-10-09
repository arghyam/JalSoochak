package org.arghyam.jalsoochak.message.ledger;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;

/**
 * What became of our attempt to hand a message to its provider — the {@code dispatch_status} of a
 * {@code notification_table} row. The provider's later verdict is {@link DeliveryState}, kept apart.
 *
 * <p>The names follow the router's {@code result=} log vocabulary, so a row and its log line agree —
 * except {@link #ACCEPTED}, which the logs call {@code SENT}: the provider took the message, and calling
 * that "sent" in a table read without the logs' caveats invites reading it as "delivered". Mirrored by
 * the {@code chk_notification_dispatch_status} constraint (V63).</p>
 */
public enum DispatchStatus {

    /** The row is open and the send is in progress. A row left here means the process died mid-send. */
    DISPATCHING,
    /** The provider accepted the message. Not a delivery. */
    ACCEPTED,
    /** A dry-run flag stopped the send before any provider call. */
    SUPPRESSED,
    /** The provider answered and refused the message. */
    PROVIDER_REJECTED,
    /** The send failed: an error on the way to the provider, or a rejection at a known stage. */
    FAILED_DELIVERY,
    /** The provider may or may not hold the message: a timeout, or an acceptance with no id. */
    DELIVERY_UNCONFIRMED,
    /** The content (a report PDF) could not be built. */
    FAILED_GENERATION,
    /** The content was built but could not be uploaded where the provider fetches it. */
    FAILED_UPLOAD,
    /** The recipient has no phone, address or provider contact to send to. */
    SKIPPED_NO_CONTACT;

    /**
     * The delivery state a row starts with once dispatch reaches this status: an accepted message the
     * provider can be asked about is pending; one it cannot is untracked; anything never handed over
     * was not sent.
     */
    public DeliveryState initialDeliveryState(boolean trackable) {
        return switch (this) {
            case ACCEPTED, DELIVERY_UNCONFIRMED -> trackable ? DeliveryState.PENDING : DeliveryState.NOT_TRACKED;
            case DISPATCHING -> DeliveryState.PENDING;
            default -> DeliveryState.NOT_SENT;
        };
    }
}
