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
        super(OPERATOR_FACING_MESSAGE);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
