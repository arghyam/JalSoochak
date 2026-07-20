package org.arghyam.jalsoochak.user.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAPTCHA verification configuration.
 * Bound from {@code captcha.*} in application.yml.
 *
 * <p>Ships dark: {@code enabled} defaults to {@code false}, so {@code verify(...)} is a no-op and
 * existing clients are unaffected. Once the frontend sends a {@code captchaToken}, ops sets
 * {@code CAPTCHA_SECRET_KEY} and flips {@code CAPTCHA_ENABLED=true} per environment.
 *
 * @param enabled   master switch; when {@code false} verification is skipped entirely
 * @param provider  reserved for a future hybrid v3 → v2 step-up; currently only {@code recaptcha-v2}
 * @param verifyUrl provider siteverify endpoint; required (non-blank) only when {@code enabled}
 * @param secretKey provider secret; required (non-blank) only when {@code enabled}
 */
@ConfigurationProperties(prefix = "captcha")
public record CaptchaProperties(
        boolean enabled,
        String provider,
        String verifyUrl,
        String secretKey
) {
    public CaptchaProperties {
        if (provider == null || provider.isBlank()) {
            provider = "recaptcha-v2";
        }
        // Fail fast: a misconfigured prod (enabled with no secret/URL) must not silently allow all logins.
        if (enabled) {
            if (verifyUrl == null || verifyUrl.isBlank())
                throw new IllegalArgumentException("captcha.verify-url must not be blank when captcha.enabled is true");
            if (secretKey == null || secretKey.isBlank())
                throw new IllegalArgumentException("captcha.secret-key must not be blank when captcha.enabled is true");
        }
    }
}
