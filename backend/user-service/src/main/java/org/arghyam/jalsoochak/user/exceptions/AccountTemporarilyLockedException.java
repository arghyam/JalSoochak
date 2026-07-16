package org.arghyam.jalsoochak.user.exceptions;

/**
 * Raised when a login is rejected because realm brute-force detection has temporarily locked the
 * account. Maps to HTTP 429 with a {@code Retry-After} header carrying {@link #getRetryAfterSeconds()}.
 */
public class AccountTemporarilyLockedException extends RuntimeException {

    private final long retryAfterSeconds;

    public AccountTemporarilyLockedException(long retryAfterSeconds) {
        super("Account temporarily locked due to too many failed login attempts. "
                + "Please try again in a few minutes.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
