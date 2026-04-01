package org.arghyam.jalsoochak.user.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.arghyam.jalsoochak.user.config.properties.CookieProperties;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.response.InviteInfoResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.TokenResponseDTO;
import org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.arghyam.jalsoochak.user.exceptions.TokenAlreadyUsedException;
import org.arghyam.jalsoochak.user.exceptions.UserAlreadyExistsException;
import org.arghyam.jalsoochak.user.service.AuthService;
import org.arghyam.jalsoochak.user.service.StaffAuthService;
import org.arghyam.jalsoochak.user.util.CookieHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

/**
 * Tests for {@link AuthController} covering validation, happy paths, and error responses.
 * Security filters are disabled; tests focus on HTTP layer behaviour.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthControllerTest.CookiePropertiesTestConfig.class)
@DisplayName("AuthController Tests")
class AuthControllerTest {

    @TestConfiguration
    static class CookiePropertiesTestConfig {
        @Bean
        CookieProperties cookieProperties() {
            return new CookieProperties(false, "Strict");
        }

        @Bean
        CookieHelper cookieHelper(CookieProperties cookieProperties) {
            return new CookieHelper(cookieProperties);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private StaffAuthService staffAuthService;

    @BeforeEach
    void resetMocks() {
        reset(authService, staffAuthService);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private AuthResult authResult(TokenResponseDTO token) {
        return new AuthResult(token, "test-rt", 1800);
    }

    // ── /login ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginTests {

        @Test
        @DisplayName("Should return 200 with access token on valid credentials")
        void login_validCredentials_returns200() throws Exception {
            TokenResponseDTO token = new TokenResponseDTO();
            token.setAccessToken("access-token");
            token.setPhoneNumber("91XXXXXXXXXX");

            when(authService.login(any())).thenReturn(authResult(token));

            String payload = """
                    {
                      "email": "user@example.com",
                      "password": "Pass@123"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.access_token").value("access-token"))
                    .andExpect(jsonPath("$.data.phone_number").value("91XXXXXXXXXX"));
        }

        @Test
        @DisplayName("Login response must NOT contain refresh_token in body")
        void login_responseBody_hasNoRefreshToken() throws Exception {
            TokenResponseDTO token = new TokenResponseDTO();
            token.setAccessToken("at");

            when(authService.login(any())).thenReturn(authResult(token));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.refresh_token").doesNotExist());
        }

        @Test
        @DisplayName("Login response must set httpOnly Set-Cookie header")
        void login_setsHttpOnlyCookie() throws Exception {
            TokenResponseDTO token = new TokenResponseDTO();
            token.setAccessToken("at");

            when(authService.login(any())).thenReturn(authResult(token));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=test-rt")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
        }

        @Test
        @DisplayName("Should return 401 on invalid credentials")
        void login_invalidCredentials_returns401() throws Exception {
            when(authService.login(any()))
                    .thenThrow(new InvalidCredentialsException("Invalid credentials"));

            String payload = """
                    {
                      "email": "user@example.com",
                      "password": "wrongpass"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("Should return 403 when account is deactivated")
        void login_deactivatedAccount_returns403() throws Exception {
            when(authService.login(any()))
                    .thenThrow(new AccountDeactivatedException("Account is deactivated"));

            String payload = """
                    {
                      "email": "user@example.com",
                      "password": "Pass@123"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }

        @Test
        @DisplayName("Missing email should return 400")
        void login_missingEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"Pass@123\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("Invalid email format should return 400")
        void login_invalidEmailFormat_returns400() throws Exception {
            String payload = """
                    {
                      "email": "not-an-email",
                      "password": "Pass@123"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── /refresh ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshTests {

        @Test
        @DisplayName("Should return 200 with new access token when cookie is present")
        void refresh_withCookie_returns200() throws Exception {
            TokenResponseDTO token = new TokenResponseDTO();
            token.setAccessToken("new-access");

            when(authService.refreshToken("valid-refresh")).thenReturn(new AuthResult(token, "new-rt", 1800));

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(new Cookie("refresh_token", "valid-refresh")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.access_token").value("new-access"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=new-rt")));
        }

        @Test
        @DisplayName("Missing refresh_token cookie should return 400")
        void refresh_noCookie_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    // ── /logout ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class LogoutTests {

        @Test
        @DisplayName("Should return 200 and clear cookie when cookie is present")
        void logout_withCookie_returns200AndClearsCookie() throws Exception {
            doNothing().when(authService).logout("valid-refresh");

            mockMvc.perform(post("/api/v1/auth/logout")
                            .cookie(new Cookie("refresh_token", "valid-refresh")))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        }

        @Test
        @DisplayName("Missing refresh_token cookie should return 200 and clear cookie (idempotent)")
        void logout_noCookie_returns200AndClearsCookie() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        }
    }

    // ── /invite/info ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/auth/invites")
    class InviteInfoTests {

        @Test
        @DisplayName("Should return 200 with invite details for a valid token")
        void inviteInfo_validToken_returns200() throws Exception {
            InviteInfoResponseDTO info = new InviteInfoResponseDTO("invited@example.com", "STATE_ADMIN", "Madhya Pradesh", "John", "Doe", "9112345678");
            when(authService.getInviteInfo("valid-token")).thenReturn(info);

            mockMvc.perform(get("/api/v1/auth/invites")
                            .param("token", "valid-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.email").value("invited@example.com"))
                    .andExpect(jsonPath("$.data.role").value("STATE_ADMIN"));
        }

        @Test
        @DisplayName("Should return 409 when account already exists")
        void inviteInfo_accountExists_returns409() throws Exception {
            when(authService.getInviteInfo(anyString()))
                    .thenThrow(new UserAlreadyExistsException("Account already exists"));

            mockMvc.perform(get("/api/v1/auth/invites")
                            .param("token", "used-token"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }
    }

    // ── /forgot-password ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/forgot-password")
    class ForgotPasswordTests {

        @Test
        @DisplayName("Should always return 200 (OWASP — no email enumeration)")
        void forgotPassword_alwaysReturns200() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"any@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }
    }

    // ── /reset-password ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/reset-password")
    class ResetPasswordTests {

        @Test
        @DisplayName("Should return 200 on successful password reset")
        void resetPassword_success_returns200() throws Exception {
            doNothing().when(authService).resetPassword(any());

            String payload = """
                    {
                      "token": "valid-token",
                      "newPassword": "NewPass@123"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }

        @Test
        @DisplayName("Should return 400 when reset link has already been used")
        void resetPassword_tokenAlreadyUsed_returns400() throws Exception {
            doThrow(new TokenAlreadyUsedException("Reset link has already been used"))
                    .when(authService).resetPassword(any());

            String payload = """
                    {
                      "token": "used-token",
                      "newPassword": "NewPass@123"
                    }
                    """;

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }
}
