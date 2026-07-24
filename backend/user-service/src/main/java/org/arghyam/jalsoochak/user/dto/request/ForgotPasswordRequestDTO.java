package org.arghyam.jalsoochak.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ForgotPasswordRequestDTO {
    @NotBlank(message = "Email is required")
    @Email(message = "Please provide a valid email address")
    private String email;

    /**
     * Optional reCAPTCHA token. Intentionally not {@code @NotBlank}: emptiness is enforced inside
     * {@code CaptchaVerificationService.verify(...)} only when {@code captcha.enabled=true}.
     */
    private String captchaToken;
}
