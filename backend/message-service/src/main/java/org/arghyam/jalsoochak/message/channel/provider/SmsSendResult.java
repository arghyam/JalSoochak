package org.arghyam.jalsoochak.message.channel.provider;

/**
 * The answer to one SMS send, for callers that record what the provider said rather than only
 * whether it said yes.
 *
 * <p>Same tri-state contract as {@link SmsSender#sendOtp}: an accepted or a non-retryable rejected
 * send arrives as a value, a transient failure as an error signal — so a caller that swallows errors to
 * avoid re-sending an expired OTP keeps doing exactly that.</p>
 *
 * @param accepted     whether the provider accepted the message
 * @param acceptance   the provider's id and word when accepted; {@code null} when rejected
 * @param errorCode    the provider's rejection code or HTTP status when rejected
 * @param errorMessage the provider's rejection text when rejected; never contains the message body
 */
public record SmsSendResult(boolean accepted, ProviderAcceptance acceptance,
                            String errorCode, String errorMessage) {

    public static SmsSendResult accepted(ProviderAcceptance acceptance) {
        return new SmsSendResult(true, acceptance, null, null);
    }

    public static SmsSendResult rejected(String errorCode, String errorMessage) {
        return new SmsSendResult(false, null, errorCode, errorMessage);
    }

    /** What {@link SmsSender#sendOtp}'s boolean becomes for an adapter that has nothing more to say. */
    public static SmsSendResult fromBoolean(boolean accepted) {
        return accepted ? accepted(ProviderAcceptance.untracked(null)) : rejected(null, null);
    }
}
