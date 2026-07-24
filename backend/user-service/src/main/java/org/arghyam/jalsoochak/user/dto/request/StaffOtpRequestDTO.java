package org.arghyam.jalsoochak.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body for {@code POST /api/v1/auth/staff/request-otp}.
 * The frontend presents a state-selector UI; users pick their state and enter their phone number.
 */
@Getter
@Setter
public class StaffOtpRequestDTO {

    /**
     * Phone number in E.164 format without the leading {@code +}.
     * Digits only, no leading zero, 7–15 digits total (e.g. {@code 919876543210}).
     */
    @NotBlank(message = "phoneNumber is required")
    @Pattern(regexp = "^[1-9]\\d{6,14}$",
             message = "phoneNumber must be digits only, no leading zero, 7–15 digits total")
    private String phoneNumber;

    /** State code selected by the user (e.g. {@code MP}, {@code TR}). Case-insensitive. */
    @NotBlank(message = "tenantCode is required")
    private String tenantCode;

    /**
     * Optional reCAPTCHA token. Intentionally not {@code @NotBlank}: emptiness is enforced inside
     * {@code CaptchaVerificationService.verify(...)} only when {@code captcha.enabled=true}.
     */
    private String captchaToken;
}
