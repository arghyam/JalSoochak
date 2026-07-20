package org.arghyam.jalsoochak.user.exceptions;

/**
 * Thrown when a request's CAPTCHA token is missing, malformed, or rejected by the provider.
 * Mapped to a generic {@code 400} so it never reveals account state (account-enumeration hardening).
 */
public class CaptchaVerificationException extends RuntimeException {
    public CaptchaVerificationException(String message) {
        super(message);
    }
}
