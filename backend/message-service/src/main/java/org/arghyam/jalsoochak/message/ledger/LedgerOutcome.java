package org.arghyam.jalsoochak.message.ledger;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.arghyam.jalsoochak.message.channel.provider.ProviderAcceptance;

/**
 * How a send ended, as {@link NotificationLedger#close} records it.
 *
 * @param status       our verdict
 * @param acceptance   what the provider handed back, when it accepted; {@code null} otherwise
 * @param failureStage where a failed send stopped, e.g. a {@code WhatsAppSendStage} name
 * @param errorCode    the provider's error key or code, or an exception class for our own failures
 * @param errorMessage what went wrong; redacted and truncated before it is stored
 * @param templateRef  the template the send actually used, when only known afterwards
 */
public record LedgerOutcome(DispatchStatus status,
                            ProviderAcceptance acceptance,
                            String failureStage,
                            String errorCode,
                            String errorMessage,
                            String templateRef) {

    /** The provider accepted the message. Pending if it gave an id, untracked if not. */
    public static LedgerOutcome accepted(ProviderAcceptance acceptance) {
        return new LedgerOutcome(DispatchStatus.ACCEPTED,
                acceptance != null ? acceptance : ProviderAcceptance.untracked(null), null, null, null, null);
    }

    /** The provider accepted the message and gave nothing to follow it up with. */
    public static LedgerOutcome acceptedUntracked(String providerStatus) {
        return accepted(ProviderAcceptance.untracked(providerStatus));
    }

    /** A dry-run flag stopped the send. */
    public static LedgerOutcome suppressed() {
        return new LedgerOutcome(DispatchStatus.SUPPRESSED, null, null, null, null, null);
    }

    /** The send did not reach acceptance. */
    public static LedgerOutcome failed(DispatchStatus status, String failureStage, String errorCode,
                                       String errorMessage) {
        return new LedgerOutcome(status, null, failureStage, errorCode, errorMessage, null);
    }

    /** As {@link #failed}, from an exception: its class names the error, its message describes it. */
    public static LedgerOutcome failed(DispatchStatus status, String failureStage, Throwable error) {
        return failed(status, failureStage,
                error == null ? null : error.getClass().getSimpleName(),
                error == null ? null : error.getMessage());
    }

    public LedgerOutcome withTemplateRef(String ref) {
        return new LedgerOutcome(status, acceptance, failureStage, errorCode, errorMessage, ref);
    }

    /** The delivery state the row starts with after this outcome. */
    public DeliveryState deliveryState() {
        return status.initialDeliveryState(acceptance != null && acceptance.isTrackable());
    }

    /** Whether the provider took the message, so the row gets a dispatch time. */
    public boolean reachedProvider() {
        return status == DispatchStatus.ACCEPTED || status == DispatchStatus.DELIVERY_UNCONFIRMED;
    }
}
