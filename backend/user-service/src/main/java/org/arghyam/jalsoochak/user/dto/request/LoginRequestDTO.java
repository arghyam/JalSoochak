package org.arghyam.jalsoochak.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequestDTO {
    @NotBlank(message = "Email is required")
    @Email(message = "Please provide a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    /**
     * Optional reCAPTCHA token. Intentionally not {@code @NotBlank}: when {@code captcha.enabled=false}
     * (dark rollout) and for existing clients it may be absent; emptiness is enforced inside
     * {@code CaptchaVerificationService.verify(...)} only when CAPTCHA is enabled.
     */
    private String captchaToken;
}