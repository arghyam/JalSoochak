package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.exceptions.CaptchaVerificationException;

/**
 * Verifies a CAPTCHA token before any credential / DB / send work on unauthenticated auth endpoints.
 *
 * <p>Called as the first line of each protected service method, so a CAPTCHA failure short-circuits
 * before any account state is touched (preserving anti-enumeration). When CAPTCHA is disabled, the
 * implementation is a no-op.
 */
public interface CaptchaVerificationService {

    /**
     * @param captchaToken the token supplied by the client (from the reCAPTCHA widget)
     * @param action       a label for the calling endpoint (e.g. {@code "login"}); used for logging
     *                     now and reserved as the seam for future v3 action-matching
     * @throws CaptchaVerificationException if verification is enabled and the token is missing or rejected
     */
    void verify(String captchaToken, String action);
}
