package org.arghyam.jalsoochak.user.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Integration tests for the server-side CAPTCHA gate with {@code captcha.enabled=true}.
 *
 * <p>Kept as a dedicated top-level class (not a {@code @Nested} block inside
 * {@link AuthControllerIntegrationTest}) so it gets its own application context where
 * {@code captcha.enabled=true} binds cleanly and the {@code captcha.verify-url} points at this
 * context's WireMock server. A valid token stub lets the request reach normal business logic;
 * a missing or provider-rejected token yields {@code 400} before any DB/Keycloak work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureWireMock(port = 0)   // sets wiremock.server.port; used below and in application-test.properties
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "captcha.enabled=true",
        "captcha.secret-key=test-captcha-secret",
        "captcha.verify-url=http://localhost:${wiremock.server.port}/recaptcha/api/siteverify"
})
@DisplayName("AuthController CAPTCHA Enforcement Integration Tests")
class AuthCaptchaControllerIntegrationTest {

    private static final String CAPTCHA_PATH = "/recaptcha/api/siteverify";

    private static final String KEYCLOAK_TOKEN_RESPONSE = """
            {"access_token":"test-at","refresh_token":"test-rt","expires_in":300,\
             "refresh_expires_in":1800,"token_type":"Bearer","scope":"openid"}
            """;

    @SuppressWarnings("resource") // lifecycle managed by @Testcontainers / @Container
    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    KeycloakProvider keycloakProvider;

    @MockBean
    UserNotificationEventPublisher userNotificationEventPublisher;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PiiEncryptionService piiEncryptionService;

    @BeforeEach
    void setUp() {
        WireMock.reset();
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_admin_user_master_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_table");
        jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = 3 WHERE id = 1");
    }

    private void seedUser(String uuid, String email, int tenantId, int adminLevel, int status) {
        jdbcTemplate.update("""
                INSERT INTO common_schema.tenant_admin_user_master_table
                    (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status)
                VALUES (?, ?, ?, ?, ?, ?, 'KEYCLOAK_MANAGED', ?)
                """, uuid, email,
                piiEncryptionService.encrypt("91XXXXXXXXXX"),
                piiEncryptionService.hmac("91XXXXXXXXXX"),
                tenantId, adminLevel, status);
    }

    private void stubKeycloakToken(int httpStatus, String body) {
        WireMock.stubFor(
                WireMock.post(urlEqualTo("/realms/jalsoochak-realm/protocol/openid-connect/token"))
                        .willReturn(aResponse()
                                .withStatus(httpStatus)
                                .withHeader("Content-Type", "application/json")
                                .withBody(body)));
    }

    private void stubCaptcha(boolean success) {
        WireMock.stubFor(
                WireMock.post(urlEqualTo(CAPTCHA_PATH))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(success
                                        ? "{\"success\":true}"
                                        : "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}")));
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login with a valid CAPTCHA token → 200")
    void login_validCaptcha_returns200() throws Exception {
        seedUser("kc-cap-1", "captcha@example.com", 0, 1, 1);
        stubCaptcha(true);
        stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"captcha@example.com\",\"password\":\"Pass@123\",\"captchaToken\":\"good\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").value("test-at"));
    }

    @Test
    @DisplayName("login with a missing CAPTCHA token → 400 (before any DB/Keycloak work)")
    void login_missingCaptcha_returns400() throws Exception {
        seedUser("kc-cap-2", "captcha2@example.com", 0, 1, 1);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"captcha2@example.com\",\"password\":\"Pass@123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("login with a provider-rejected CAPTCHA token → 400")
    void login_invalidCaptcha_returns400() throws Exception {
        seedUser("kc-cap-3", "captcha3@example.com", 0, 1, 1);
        stubCaptcha(false);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"captcha3@example.com\",\"password\":\"Pass@123\",\"captchaToken\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── forgot-password ────────────────────────────────────────────────────────

    @Test
    @DisplayName("forgot-password with a valid CAPTCHA token → 200")
    void forgotPassword_validCaptcha_returns200() throws Exception {
        stubCaptcha(true);

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"captchaToken\":\"good\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("forgot-password with a missing CAPTCHA token → 400")
    void forgotPassword_missingCaptcha_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── staff OTP ──────────────────────────────────────────────────────────────
    // Note: these payloads use an all-digits placeholder (919999999999) rather than the
    // project-standard 91XXXXXXXXXX PII mask because StaffOtpRequestDTO.phoneNumber enforces
    // a digits-only @Pattern; the masked form would fail bean validation and mask what these
    // tests actually assert (the CAPTCHA gate). The number is not a real subscriber.

    @Test
    @DisplayName("staff OTP with a missing CAPTCHA token → 400 (before tenant/phone lookup)")
    void staffOtp_missingCaptcha_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/staff/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"919999999999\",\"tenantCode\":\"MP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("staff OTP with a valid CAPTCHA token passes the gate and runs business logic (unregistered phone → 404)")
    void staffOtp_validCaptcha_passesGate() throws Exception {
        stubCaptcha(true);

        mockMvc.perform(post("/api/v1/auth/staff/otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"919999999999\",\"tenantCode\":\"MP\",\"captchaToken\":\"good\"}"))
                .andExpect(status().isNotFound());
    }
}
