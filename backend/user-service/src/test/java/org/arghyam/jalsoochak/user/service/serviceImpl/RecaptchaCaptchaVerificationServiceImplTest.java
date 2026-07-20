package org.arghyam.jalsoochak.user.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.arghyam.jalsoochak.user.clients.CaptchaClient;
import org.arghyam.jalsoochak.user.clients.RecaptchaVerifyResponse;
import org.arghyam.jalsoochak.user.config.properties.CaptchaProperties;
import org.arghyam.jalsoochak.user.exceptions.CaptchaVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecaptchaCaptchaVerificationServiceImpl")
class RecaptchaCaptchaVerificationServiceImplTest {

    private static final String VERIFY_URL = "https://verify.example/siteverify";
    private static final String SECRET = "secret-key";

    @Mock
    CaptchaClient captchaClient;

    private RecaptchaCaptchaVerificationServiceImpl service(boolean enabled) {
        CaptchaProperties props = new CaptchaProperties(enabled, "recaptcha-v2",
                enabled ? VERIFY_URL : "", enabled ? SECRET : "");
        return new RecaptchaCaptchaVerificationServiceImpl(props, captchaClient);
    }

    @Test
    @DisplayName("disabled: no-op, provider never called even with a blank token")
    void disabled_isNoOp() {
        RecaptchaCaptchaVerificationServiceImpl svc = service(false);

        assertDoesNotThrow(() -> svc.verify(null, "login"));
        assertDoesNotThrow(() -> svc.verify("any-token", "login"));

        verifyNoInteractions(captchaClient);
    }

    @Test
    @DisplayName("enabled + blank token: throws without a provider call")
    void enabled_blankToken_throws() {
        RecaptchaCaptchaVerificationServiceImpl svc = service(true);

        assertThrows(CaptchaVerificationException.class, () -> svc.verify("  ", "login"));
        assertThrows(CaptchaVerificationException.class, () -> svc.verify(null, "login"));

        verify(captchaClient, never()).verify(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("enabled + provider returns success=false: throws")
    void enabled_providerFailure_throws() {
        RecaptchaCaptchaVerificationServiceImpl svc = service(true);
        when(captchaClient.verify(VERIFY_URL, SECRET, "bad-token"))
                .thenReturn(new RecaptchaVerifyResponse(false, null, null, List.of("invalid-input-response")));

        assertThrows(CaptchaVerificationException.class, () -> svc.verify("bad-token", "login"));
    }

    @Test
    @DisplayName("enabled + provider returns success=true: passes and forwards secret+token")
    void enabled_providerSuccess_passes() {
        RecaptchaCaptchaVerificationServiceImpl svc = service(true);
        when(captchaClient.verify(VERIFY_URL, SECRET, "good-token"))
                .thenReturn(new RecaptchaVerifyResponse(true, "2026-07-16T00:00:00Z", "localhost", null));

        assertDoesNotThrow(() -> svc.verify("good-token", "login"));

        verify(captchaClient).verify(VERIFY_URL, SECRET, "good-token");
    }
}
