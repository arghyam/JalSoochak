package org.arghyam.jalsoochak.message.channel;

/**
 * What is known about a nudge flow start after the call returns.
 *
 * <p>Starting a provider flow is not idempotent: every start sends the operator the nudge template again.
 * So a failure is retried only when the request certainly never took effect.</p>
 */
public enum NudgeSendOutcome {
    /** The WhatsApp provider accepted the flow start. */
    SENT,
    /** The request never reached the provider, or the provider rejected it unprocessed — safe to retry. */
    NOT_SENT,
    /** The provider may or may not have started the flow (timeout, error answer) — retrying risks a duplicate. */
    UNKNOWN
}
