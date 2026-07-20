package org.arghyam.jalsoochak.user.service.serviceImpl;

import org.arghyam.jalsoochak.user.clients.CaptchaClient;
import org.arghyam.jalsoochak.user.clients.RecaptchaVerifyResponse;
import org.arghyam.jalsoochak.user.config.properties.CaptchaProperties;
import org.arghyam.jalsoochak.user.exceptions.CaptchaVerificationException;
import org.arghyam.jalsoochak.user.service.CaptchaVerificationService;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Google reCAPTCHA v2 implementation of {@link CaptchaVerificationService}.
 *
 * <p>When {@code captcha.enabled=false} this is a no-op (dark rollout). When enabled, a blank token
 * is rejected without a network call, otherwise the token is verified against the provider and a
 * {@code success=false} result is rejected. All rejections throw {@link CaptchaVerificationException}
 * with the same generic message so a caller cannot distinguish missing vs. invalid vs. failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecaptchaCaptchaVerificationServiceImpl implements CaptchaVerificationService {

    private static final String GENERIC_FAILURE_MESSAGE = "CAPTCHA verification failed";

    private final CaptchaProperties captchaProperties;
    private final CaptchaClient captchaClient;

    @Override
    public void verify(String captchaToken, String action) {
        if (!captchaProperties.enabled()) {
            return; // Dark: verification disabled, no behaviour change.
        }
        if (captchaToken == null || captchaToken.isBlank()) {
            log.warn("CAPTCHA rejected: missing token action={}", action);
            throw new CaptchaVerificationException(GENERIC_FAILURE_MESSAGE);
        }

        RecaptchaVerifyResponse response =
                captchaClient.verify(captchaProperties.verifyUrl(), captchaProperties.secretKey(), captchaToken);

        if (response == null || !response.success()) {
            log.warn("CAPTCHA rejected: provider returned failure action={} errorCodes={}",
                    action, response != null ? response.errorCodes() : null);
            throw new CaptchaVerificationException(GENERIC_FAILURE_MESSAGE);
        }
    }
}
