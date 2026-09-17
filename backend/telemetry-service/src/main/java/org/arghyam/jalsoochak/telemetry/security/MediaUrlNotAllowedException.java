package org.arghyam.jalsoochak.telemetry.security;

/**
 * A caller-supplied media URL that the fetch policy refused.
 *
 * <p>The exception message is the same operator-facing text used for any other unusable media, so a
 * probe learns nothing from the reply about what the policy allows. The machine-readable
 * {@link #getReason()} is for logs and tests only, and must not be surfaced to the caller.
 */
public class MediaUrlNotAllowedException extends IllegalStateException {

    private static final String OPERATOR_FACING_MESSAGE = "Invalid media. Please send a clear meter image.";

    private final String reason;

    public MediaUrlNotAllowedException(String reason) {
        this(reason, null);
    }

    /**
     * @param cause the parse or resolution failure the verdict was drawn from, kept for the stack
     *              trace only — it is never rendered into the operator-facing message.
     */
    public MediaUrlNotAllowedException(String reason, Throwable cause) {
        super(OPERATOR_FACING_MESSAGE, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
