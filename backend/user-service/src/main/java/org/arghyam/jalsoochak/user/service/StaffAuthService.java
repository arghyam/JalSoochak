package org.arghyam.jalsoochak.user.service;

import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpVerifyDTO;
import org.arghyam.jalsoochak.user.dto.response.OtpRequestResponseDTO;

public interface StaffAuthService {

    /**
     * Requests an OTP for a staff user and returns the configured OTP length for frontend rendering.
     *
     * <p>Anti-enumeration: returns the same response regardless of whether the phone is
     * registered, the account is inactive, or a cooldown is active — any observable difference
     * would reveal whether a phone number is registered.
     *
     * <p>Exceptions to anti-enumeration (intentional, by design):
     * <ul>
     *   <li>System users (SUPER_USER / STATE_ADMIN) receive a 400 directing them to email/password login.</li>
     * </ul>
     *
     * @throws org.arghyam.jalsoochak.user.exceptions.BadRequestException if the phone belongs to
     *         a system user who must use email/password login instead
     */
    OtpRequestResponseDTO requestOtp(StaffOtpRequestDTO request);

    /**
     * Verifies the OTP and returns a Keycloak access token on success.
     *
     * @throws org.arghyam.jalsoochak.user.exceptions.BadRequestException      if OTP is invalid/expired
     * @throws org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException if account is inactive
     */
    AuthResult verifyOtp(StaffOtpVerifyDTO request);
}
