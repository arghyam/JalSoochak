package org.arghyam.jalsoochak.user.dto.response;

/**
 * Response payload for {@code POST /api/v1/auth/staff/otp}.
 * Carries the configured OTP length so the frontend can render
 * the correct number of input boxes without hard-coding the value.
 */
public record OtpRequestResponseDTO(int otpLength) {}
