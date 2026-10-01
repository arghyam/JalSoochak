package org.arghyam.jalsoochak.message.channel;

/**
 * What is known about a nudge flow start after the call returns.
 *
 * <p>Starting a Glific flow is not idempotent: every start sends the operator the nudge template again.
 * So a failure is retried only when the request certainly never took effect.</p>
 */
public enum NudgeSendOutcome {
    /** Glific accepted the flow start. */
    SENT,
    /** The request never reached Glific, or Glific rejected it unprocessed — safe to retry. */
    NOT_SENT,
    /** Glific may or may not have started the flow (timeout, error answer) — retrying risks a duplicate. */
    UNKNOWN
}
