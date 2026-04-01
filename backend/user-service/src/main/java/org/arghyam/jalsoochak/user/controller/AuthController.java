package org.arghyam.jalsoochak.user.controller;

import org.arghyam.jalsoochak.user.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.ActivateAccountRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.ForgotPasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.LoginRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.ResetPasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpVerifyDTO;
import org.arghyam.jalsoochak.user.dto.response.InviteInfoResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.TokenResponseDTO;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.service.AuthService;
import org.arghyam.jalsoochak.user.service.StaffAuthService;
import org.arghyam.jalsoochak.user.util.CookieHelper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, token refresh, logout, account activation, and password reset")
public class AuthController {

    private final AuthService authService;
    private final StaffAuthService staffAuthService;
    private final CookieHelper cookieHelper;

    @Operation(summary = "Login",
            description = "Authenticate with email and password. Sets an HttpOnly refresh token cookie on success.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful – returns access token"),
        @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials, account not yet activated, or deactivated",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponseDTO<TokenResponseDTO>> login(
            @Valid @RequestBody LoginRequestDTO request,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/login – Login attempt");
        AuthResult result = authService.login(request);
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHelper.buildRefreshCookie(result.refreshToken(), result.refreshExpiresIn()).toString());
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Login successful", result.tokenResponse()));
    }

    @Operation(summary = "Refresh access token",
            description = "Exchange the HttpOnly refresh token cookie for a new access token.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token refreshed"),
        @ApiResponse(responseCode = "400", description = "Refresh token cookie is missing",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
        @ApiResponse(responseCode = "401", description = "Refresh token is invalid or expired",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponseDTO<TokenResponseDTO>> refresh(
            @CookieValue(name = CookieHelper.REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/refresh – Token refresh request");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadRequestException("Refresh token cookie is missing");
        }
        AuthResult result = authService.refreshToken(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHelper.buildRefreshCookie(result.refreshToken(), result.refreshExpiresIn()).toString());
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Token refreshed", result.tokenResponse()));
    }

    @Operation(summary = "Logout",
            description = "Revoke the refresh token in Keycloak and clear the HttpOnly cookie.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Logged out successfully"),
        @ApiResponse(responseCode = "502", description = "Keycloak session revocation failed; local cookie was still cleared",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/logout")
    public ResponseEntity<ApiResponseDTO<Void>> logout(
            @CookieValue(name = CookieHelper.REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/logout");
        if (refreshToken == null || refreshToken.isBlank()) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clearRefreshCookie().toString());
            return ResponseEntity.ok(ApiResponseDTO.of(200, "Logged out successfully"));
        }
        try {
            authService.logout(refreshToken);
        } catch (Exception e) {
            log.error("Logout failed for session: {}", e.getMessage());
            response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clearRefreshCookie().toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponseDTO.of(502, "Logout failed; your session may still be active on the server"));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHelper.clearRefreshCookie().toString());
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Logged out successfully"));
    }

    @Operation(summary = "Get invite info",
            description = "Retrieve invite metadata (email, role, tenant name) for a given invite token.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Invite info retrieved"),
        @ApiResponse(responseCode = "400", description = "Token is invalid or expired",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
        @ApiResponse(responseCode = "409", description = "Account already exists",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @GetMapping("/invite/info")
    public ResponseEntity<ApiResponseDTO<InviteInfoResponseDTO>> inviteInfo(@RequestParam String token) {
        log.info("GET /api/v1/auth/invite/info");
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Invite info retrieved", authService.getInviteInfo(token)));
    }

    @Operation(summary = "Activate account",
            description = "Complete registration by consuming the invite token and setting a password. Returns an access token.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account activated – returns access token"),
        @ApiResponse(responseCode = "400", description = "Invite link is invalid, expired, or already used",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
        @ApiResponse(responseCode = "409", description = "Account already exists",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/activate-account")
    public ResponseEntity<ApiResponseDTO<TokenResponseDTO>> activateAccount(
            @Valid @RequestBody ActivateAccountRequestDTO request,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/activate-account");
        AuthResult result = authService.activateAccount(request);
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHelper.buildRefreshCookie(result.refreshToken(), result.refreshExpiresIn()).toString());
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Account activated successfully", result.tokenResponse()));
    }

    @Operation(summary = "Forgot password",
            description = "Request a password reset email. Response is identical whether or not the email exists (OWASP anti-enumeration).",
            security = {})
    @ApiResponse(responseCode = "200", description = "If the email is registered, a reset link has been sent")
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponseDTO<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDTO request) {
        log.info("POST /api/v1/auth/forgot-password");
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "If this email is registered, a reset link has been sent"));
    }

    @Operation(summary = "Reset password",
            description = "Set a new password by supplying a valid, unexpired, single-use reset token.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password reset successfully"),
        @ApiResponse(responseCode = "400", description = "Reset link is invalid, expired, or already used",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponseDTO<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequestDTO request) {
        log.info("POST /api/v1/auth/reset-password");
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Password reset successfully"));
    }

    @Operation(summary = "Request staff OTP",
            description = "Send a login OTP to a staff user's phone via WhatsApp or SMS. " +
                          "Response is identical whether or not the phone is registered (OWASP anti-enumeration).",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OTP sent if the phone is registered and active"),
        @ApiResponse(responseCode = "400", description = "Validation error or cooldown active",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/staff/request-otp")
    public ResponseEntity<ApiResponseDTO<Void>> staffRequestOtp(@Valid @RequestBody StaffOtpRequestDTO request) {
        log.info("POST /api/v1/auth/staff/request-otp");
        staffAuthService.requestOtp(request);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "OTP sent if this number is registered"));
    }

    @Operation(summary = "Verify staff OTP",
            description = "Verify the OTP and obtain a Keycloak access token. Sets an HttpOnly refresh token cookie on success.",
            security = {})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "OTP verified – returns access token"),
        @ApiResponse(responseCode = "400", description = "OTP is invalid or expired",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
        @ApiResponse(responseCode = "401", description = "Account is deactivated",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PostMapping("/staff/verify-otp")
    public ResponseEntity<ApiResponseDTO<TokenResponseDTO>> staffVerifyOtp(
            @Valid @RequestBody StaffOtpVerifyDTO request,
            HttpServletResponse response) {
        log.info("POST /api/v1/auth/staff/verify-otp");
        AuthResult result = staffAuthService.verifyOtp(request);
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookieHelper.buildRefreshCookie(result.refreshToken(), result.refreshExpiresIn()).toString());
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Login successful", result.tokenResponse()));
    }
}
