package org.arghyam.jalsoochak.user.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDateTime;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.Keycloak;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.client.WireMock;

import jakarta.servlet.http.Cookie;
import jakarta.ws.rs.core.Response;

/**
 * Integration tests for {@link AuthController} using a real PostgreSQL database
 * (Testcontainers) and WireMock (via @AutoConfigureWireMock) for the Keycloak token endpoint.
 * KeycloakProvider (admin client) and MailService are mocked via @MockBean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureWireMock(port = 0)   // sets wiremock.server.port; used in application-test.properties
@Testcontainers
@ActiveProfiles("test")
@DisplayName("AuthController Integration Tests")
class AuthControllerIntegrationTest {

    // Tenant status codes — mirrors TenantStatusEnum in tenant-service
    private static final int TENANT_STATUS_INACTIVE   = 0;
    private static final int TENANT_STATUS_ONBOARDED  = 1;
    private static final int TENANT_STATUS_CONFIGURED = 2;
    private static final int TENANT_STATUS_SUSPENDED  = 4;
    private static final int TENANT_STATUS_DEGRADED   = 5;
    private static final int TENANT_STATUS_ARCHIVED   = 6;

    // admin_level values from user_type_master_table (see sql/test-schema.sql)
    private static final int ADMIN_LEVEL_SUPER_USER  = 1;
    private static final int ADMIN_LEVEL_STATE_ADMIN = 2;

    private static final String KEYCLOAK_TOKEN_RESPONSE = """
            {"access_token":"test-at","refresh_token":"test-rt","expires_in":300,\
             "refresh_expires_in":1800,"token_type":"Bearer","scope":"openid"}
            """;

    /** A minimal JWT with payload {"sub":"kc-uuid"} — required by refreshToken() which calls extractSubFromTrustedKeycloakJwt(). */
    private static final String FAKE_JWT =
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJrYy11dWlkIn0.fake_sig";

    private static final String KEYCLOAK_JWT_TOKEN_RESPONSE =
            "{\"access_token\":\"" + FAKE_JWT + "\",\"refresh_token\":\"test-rt-new\"," +
            "\"expires_in\":300,\"refresh_expires_in\":1800,\"token_type\":\"Bearer\",\"scope\":\"openid\"}";

    // ── Testcontainers ─────────────────────────────────────────────────────────

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

    // ── Mocks ──────────────────────────────────────────────────────────────────

    @MockBean
    KeycloakProvider keycloakProvider;

    @MockBean
    UserNotificationEventPublisher userNotificationEventPublisher;

    // ── Wiring ─────────────────────────────────────────────────────────────────

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PiiEncryptionService piiEncryptionService;

    // ── Setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        WireMock.reset();

        // Clean test data between tests
        jdbcTemplate.execute("DELETE FROM common_schema.admin_user_token_table");
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_admin_user_master_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_table");
        jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = 3 WHERE id = 1");

        // Default deep-stub for Keycloak admin (void chains are no-ops by default)
        Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
        lenient().when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
        lenient().when(keycloakProvider.getRealm()).thenReturn("jalsoochak-realm");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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

    private void seedToken(String email, String rawToken, String tokenType,
                           String metadataJson, LocalDateTime expiresAt) {
        String hash = sha256Hex(rawToken);
        jdbcTemplate.update("""
                INSERT INTO common_schema.admin_user_token_table
                    (email, token_hash, token_type, metadata, expires_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, email, hash, tokenType, metadataJson, expiresAt);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // WireMock.post (from com.github.tomakehurst.wiremock.client.WireMock) — no import conflict
    // with MockMvcRequestBuilders.post which is statically imported above.
    private void stubKeycloakToken(int httpStatus, String body) {
        WireMock.stubFor(
                WireMock.post(urlEqualTo("/realms/jalsoochak-realm/protocol/openid-connect/token"))
                        .willReturn(aResponse()
                                .withStatus(httpStatus)
                                .withHeader("Content-Type", "application/json")
                                .withBody(body)));
    }

    private void stubKeycloakUserCreation(String returnedUuid) {
        Keycloak deepAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
        when(keycloakProvider.getAdminInstance()).thenReturn(deepAdmin);
        when(keycloakProvider.getRealm()).thenReturn("jalsoochak-realm");
        Response createResp = Response
                .created(URI.create("http://localhost/admin/realms/jalsoochak-realm/users/" + returnedUuid))
                .build();
        when(deepAdmin.realm(anyString()).users().create(any())).thenReturn(createResp);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/v1/auth/login
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginTests {

        @Test
        @DisplayName("SUPER_USER: sets cookie, returns access token + phoneNumber, no refresh_token in body")
        void login_superUser_setsHttpOnlyCookieAndReturnsProfile() throws Exception {
            seedUser("kc-su-1", "super@example.com", 0, 1, 1);
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"super@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.access_token").value("test-at"))
                    .andExpect(jsonPath("$.data.user_role").value("SUPER_USER"))
                    .andExpect(jsonPath("$.data.phone_number").value("91XXXXXXXXXX"))
                    .andExpect(jsonPath("$.data.name").doesNotExist())
                    .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=test-rt")))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
        }

        @Test
        @DisplayName("STATE_ADMIN: name populated from tenant user_table")
        void login_stateAdmin_populatesNameInResponse() throws Exception {
            jdbcTemplate.update("""
                    INSERT INTO common_schema.tenant_admin_user_master_table
                        (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status)
                    VALUES ('kc-sa-1', 'sa@example.com', ?, ?, 1, 2, 'KEYCLOAK_MANAGED', 1)
                    """,
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            jdbcTemplate.update("""
                    INSERT INTO tenant_mp.user_table
                        (tenant_id, title, email, user_type, phone_number, phone_number_hash, password, status,
                         email_verification_status, phone_verification_status, created_by, updated_by)
                    VALUES (1, ?, 'sa@example.com', 2, ?, ?,
                            'KEYCLOAK_MANAGED', 1, true, true, 0, 0)
                    """,
                    piiEncryptionService.encrypt("State Admin"),
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"sa@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user_role").value("STATE_ADMIN"))
                    .andExpect(jsonPath("$.data.name").value("State Admin"))
                    .andExpect(jsonPath("$.data.tenant_code").value("MP"))
                    .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
        }

        @Test
        @DisplayName("Login with deactivated account → 403")
        void login_deactivatedAccount_returns403() throws Exception {
            seedUser("kc-deact", "deactivated@example.com", 0, 1, 0);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"deactivated@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isForbidden());
        }

        @ParameterizedTest(name = "tenant status {0} → 403")
        @ValueSource(ints = {TENANT_STATUS_INACTIVE, TENANT_STATUS_SUSPENDED, TENANT_STATUS_ARCHIVED})
        @DisplayName("STATE_ADMIN login is blocked for INACTIVE, SUSPENDED and ARCHIVED tenants → 403")
        void login_stateAdmin_blockedTenantStatus_returns403(int tenantStatus) throws Exception {
            jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = ? WHERE id = 1", tenantStatus);
            seedUser("kc-blocked-sa", "blocked-sa@example.com", 1, ADMIN_LEVEL_STATE_ADMIN, 1);
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"blocked-sa@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SUPER_USER on the system tenant bypasses the tenant gate entirely → 200")
        void login_superUser_systemTenant_bypassesTenantStatusCheck() throws Exception {
            // The system tenant (id 0) has no row in tenant_master_table, so if the login ever
            // looked its status up the request would fail with "Tenant not found". Asserting the
            // missing row is what makes the 200 below proof of the bypass rather than of a
            // permissive status.
            Integer systemTenantRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.tenant_master_table WHERE id = 0", Integer.class);
            assertEquals(0, systemTenantRows);

            // Put every real tenant in a blocked status — none of them is the SUPER_USER's tenant.
            jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = ?", TENANT_STATUS_SUSPENDED);
            // tenantId 0 = system tenant, which is the SUPER_USER's home tenant
            seedUser("kc-super", "super@example.com", 0, ADMIN_LEVEL_SUPER_USER, 1);
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"super@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user_role").value("SUPER_USER"))
                    .andExpect(jsonPath("$.data.tenant_id").doesNotExist());
        }

        @Test
        @DisplayName("STATE_ADMIN login with CONFIGURED tenant → 200")
        void login_stateAdmin_configuredTenant_returns200() throws Exception {
            jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = ? WHERE id = 1", TENANT_STATUS_CONFIGURED);
            jdbcTemplate.update("""
                    INSERT INTO common_schema.tenant_admin_user_master_table
                        (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status)
                    VALUES ('kc-cfg-sa', 'configured-sa@example.com', ?, ?, 1, 2, 'KEYCLOAK_MANAGED', 1)
                    """,
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            jdbcTemplate.update("""
                    INSERT INTO tenant_mp.user_table
                        (tenant_id, title, email, user_type, phone_number, phone_number_hash, password, status,
                         email_verification_status, phone_verification_status, created_by, updated_by)
                    VALUES (1, ?, 'configured-sa@example.com', 2, ?, ?,
                            'KEYCLOAK_MANAGED', 1, true, true, 0, 0)
                    """,
                    piiEncryptionService.encrypt("Config Admin"),
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"configured-sa@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Login with ONBOARDED tenant → 200 (accessible for system users)")
        void login_onboardedTenant_returns200() throws Exception {
            jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = ? WHERE id = 1", TENANT_STATUS_ONBOARDED);
            jdbcTemplate.update("""
                    INSERT INTO common_schema.tenant_admin_user_master_table
                        (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status)
                    VALUES ('kc-onb', 'onboarded@example.com', ?, ?, 1, 2, 'KEYCLOAK_MANAGED', 1)
                    """,
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"onboarded@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Login with DEGRADED tenant → 200 (still operational)")
        void login_degradedTenant_returns200() throws Exception {
            jdbcTemplate.update("UPDATE common_schema.tenant_master_table SET status = ? WHERE id = 1", TENANT_STATUS_DEGRADED);
            jdbcTemplate.update("""
                    INSERT INTO common_schema.tenant_admin_user_master_table
                        (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status)
                    VALUES ('kc-deg', 'degraded@example.com', ?, ?, 1, 2, 'KEYCLOAK_MANAGED', 1)
                    """,
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            jdbcTemplate.update("""
                    INSERT INTO tenant_mp.user_table
                        (tenant_id, title, email, user_type, phone_number, phone_number_hash, password, status,
                         email_verification_status, phone_verification_status, created_by, updated_by)
                    VALUES (1, ?, 'degraded@example.com', 2, ?, ?,
                            'KEYCLOAK_MANAGED', 1, true, true, 0, 0)
                    """,
                    piiEncryptionService.encrypt("Degraded Admin"),
                    piiEncryptionService.encrypt("91XXXXXXXXXX"),
                    piiEncryptionService.hmac("91XXXXXXXXXX"));
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"degraded@example.com\",\"password\":\"Pass@123\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Login with wrong password → Keycloak 401 → 401")
        void login_wrongPassword_returns401() throws Exception {
            seedUser("kc-wp", "user@example.com", 0, 1, 1);
            stubKeycloakToken(401, "{\"error\":\"invalid_grant\"}");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/v1/auth/refresh  +  POST /api/v1/auth/logout
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshTests {

        @Test
        @DisplayName("Refresh with cookie → 200, new cookie issued")
        void refresh_withCookie_returns200AndNewCookie() throws Exception {
            seedUser("kc-uuid", "refresh@example.com", 0, 1, 1);
            stubKeycloakToken(200, KEYCLOAK_JWT_TOKEN_RESPONSE);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(new Cookie("refresh_token", "test-rt")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.access_token").value(FAKE_JWT))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=")));
        }

        @Test
        @DisplayName("Refresh without cookie → 400")
        void refresh_noCookie_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class LogoutTests {

        @Test
        @DisplayName("Logout with cookie → 200 and cookie cleared (Max-Age=0)")
        void logout_withCookie_clearsCookie() throws Exception {
            WireMock.stubFor(
                    WireMock.post(urlEqualTo("/realms/jalsoochak-realm/protocol/openid-connect/logout"))
                            .willReturn(aResponse().withStatus(204)));

            mockMvc.perform(post("/api/v1/auth/logout")
                            .cookie(new Cookie("refresh_token", "test-rt")))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
        }

        @Test
        @DisplayName("Logout without cookie → 200 (cookie cleared, no error)")
        void logout_noCookie_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/v1/auth/invites
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/auth/invites")
    class InviteInfoTests {

        @Test
        @DisplayName("Valid invite token → 200 with email, role, tenantName, firstName, lastName, phoneNumber")
        void inviteInfo_validToken_returnsPrefillData() throws Exception {
            // Seed the PENDING user so phone number can be fetched from DB
            jdbcTemplate.update("""
                    INSERT INTO common_schema.tenant_admin_user_master_table
                        (uuid, email, phone_number, phone_number_hash, tenant_id, admin_level, password, status)
                    VALUES (gen_random_uuid()::TEXT, 'new@example.com', ?, ?, 1, 2, 'KEYCLOAK_MANAGED', 2)
                    """,
                    piiEncryptionService.encrypt("9112345678"),
                    piiEncryptionService.hmac("9112345678"));
            String rawToken = "raw-invite-token-info";
            seedToken("new@example.com", rawToken, "INVITE",
                    "{\"role\":\"STATE_ADMIN\",\"tenantCode\":\"MP\",\"tenantName\":\"Madhya Pradesh\",\"firstName\":\"John\",\"lastName\":\"Doe\"}",
                    LocalDateTime.now().plusHours(24));

            mockMvc.perform(get("/api/v1/auth/invites").param("token", rawToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value("new@example.com"))
                    .andExpect(jsonPath("$.data.role").value("STATE_ADMIN"))
                    .andExpect(jsonPath("$.data.tenantName").value("Madhya Pradesh"))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.lastName").value("Doe"))
                    .andExpect(jsonPath("$.data.phoneNumber").value("9112345678"));
        }

        @Test
        @DisplayName("Expired invite token → 400")
        void inviteInfo_expiredToken_returns400() throws Exception {
            String rawToken = "raw-invite-token-expired";
            seedToken("new@example.com", rawToken, "INVITE",
                    "{\"role\":\"SUPER_USER\"}",
                    LocalDateTime.now().minusHours(1));

            mockMvc.perform(get("/api/v1/auth/invites").param("token", rawToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Email already exists → 409")
        void inviteInfo_emailAlreadyExists_returns409() throws Exception {
            seedUser("kc-ex", "existing@example.com", 0, 1, 1);
            String rawToken = "raw-invite-token-existing";
            seedToken("existing@example.com", rawToken, "INVITE",
                    "{\"role\":\"SUPER_USER\"}",
                    LocalDateTime.now().plusHours(24));

            mockMvc.perform(get("/api/v1/auth/invites").param("token", rawToken))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Invalid token (no DB row) → 400")
        void inviteInfo_invalidToken_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/auth/invites").param("token", "no-such-token"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/v1/auth/invites/activate
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/auth/invites/activate")
    class ActivateAccountTests {

        @Test
        @DisplayName("SUPER_USER: valid invite token → Keycloak user created, DB row inserted, cookie set, 200")
        void activateAccount_superUser_createsDbRowAndReturnsToken() throws Exception {
            // Seed a PENDING user record (status=2) as created by inviteUser at invite time
            seedUser("pending-su-uuid", "newsuper@example.com", 0, 1, 2);
            String rawToken = "raw-invite-token-su";
            seedToken("newsuper@example.com", rawToken, "INVITE",
                    "{\"role\":\"SUPER_USER\"}",
                    LocalDateTime.now().plusHours(24));

            stubKeycloakUserCreation("new-kc-uuid");
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            String payload = String.format("""
                    {"inviteToken":"%s","password":"Pass@123",\
                     "firstName":"New","lastName":"Super","phoneNumber":"9112345678"}
                    """, rawToken);

            mockMvc.perform(post("/api/v1/auth/invites/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.access_token").value("test-at"))
                    .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.tenant_admin_user_master_table WHERE email = ?",
                    Integer.class, "newsuper@example.com");
            assertEquals(1, count);

            LocalDateTime usedAt = jdbcTemplate.queryForObject(
                    "SELECT used_at FROM common_schema.admin_user_token_table WHERE email = ? AND token_type = 'INVITE'",
                    LocalDateTime.class, "newsuper@example.com");
            assertNotNull(usedAt);
        }

        @Test
        @DisplayName("STATE_ADMIN: valid invite token → rows in both schemas, name in response")
        void activateAccount_stateAdmin_createsDualDbRows() throws Exception {
            // Seed a PENDING user record (status=2) for the STATE_ADMIN being activated (tenant_id=1 = MP)
            seedUser("pending-sa-uuid", "newsa@example.com", 1, 2, 2);
            String rawToken = "raw-invite-token-sa";
            seedToken("newsa@example.com", rawToken, "INVITE",
                    "{\"role\":\"STATE_ADMIN\",\"tenantCode\":\"MP\",\"tenantName\":\"Madhya Pradesh\"}",
                    LocalDateTime.now().plusHours(24));

            stubKeycloakUserCreation("sa-kc-uuid");
            stubKeycloakToken(200, KEYCLOAK_TOKEN_RESPONSE);

            String payload = String.format("""
                    {"inviteToken":"%s","password":"Pass@123",\
                     "firstName":"State","lastName":"Admin","phoneNumber":"9198765432"}
                    """, rawToken);

            mockMvc.perform(post("/api/v1/auth/invites/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.access_token").value("test-at"))
                    .andExpect(jsonPath("$.data.name").value("State Admin"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));

            Integer commonCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.tenant_admin_user_master_table WHERE email = ?",
                    Integer.class, "newsa@example.com");
            assertEquals(1, commonCount);

            Integer tenantCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant_mp.user_table WHERE email = ?",
                    Integer.class, "newsa@example.com");
            assertEquals(1, tenantCount);
        }

        @Test
        @DisplayName("Expired invite token → 400")
        void activateAccount_expiredToken_returns400() throws Exception {
            String rawToken = "raw-invite-token-expired-act";
            seedToken("new@example.com", rawToken, "INVITE",
                    "{\"role\":\"SUPER_USER\"}",
                    LocalDateTime.now().minusHours(1));

            String payload = String.format("""
                    {"inviteToken":"%s","password":"Pass@123",\
                     "firstName":"New","lastName":"User","phoneNumber":"9112345678"}
                    """, rawToken);

            mockMvc.perform(post("/api/v1/auth/invites/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Email already registered → 409")
        void activateAccount_emailAlreadyRegistered_returns409() throws Exception {
            seedUser("kc-dup", "dup@example.com", 0, 1, 1);
            String rawToken = "raw-invite-token-dup";
            seedToken("dup@example.com", rawToken, "INVITE",
                    "{\"role\":\"SUPER_USER\"}",
                    LocalDateTime.now().plusHours(24));

            String payload = String.format("""
                    {"inviteToken":"%s","password":"Pass@123",\
                     "firstName":"Dup","lastName":"User","phoneNumber":"9112345678"}
                    """, rawToken);

            mockMvc.perform(post("/api/v1/auth/invites/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isConflict());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/v1/auth/forgot-password  +  POST /api/v1/auth/reset-password
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("forgot-password + reset-password flow")
    class PasswordResetFlowTests {

        @Test
        @DisplayName("forgot-password always returns 200 (OWASP — no email enumeration)")
        void forgotPassword_alwaysReturns200() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }

        @Test
        @DisplayName("forgot-password with registered email → 200 (same response, no disclosure)")
        void forgotPassword_registeredEmail_returns200WithoutDisclosure() throws Exception {
            seedUser("kc-fp", "user@example.com", 0, 1, 1);

            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200));
        }

        @Test
        @DisplayName("reset-password with valid token → 200 and used_at stamped in DB")
        void resetPassword_validToken_succeeds() throws Exception {
            seedUser("kc-rp", "reset@example.com", 0, 1, 1);
            String rawToken = "raw-reset-token-valid";
            seedToken("reset@example.com", rawToken, "RESET", null,
                    LocalDateTime.now().plusHours(1));

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format(
                                    "{\"token\":\"%s\",\"newPassword\":\"NewPass@123\"}", rawToken)))
                    .andExpect(status().isOk());

            LocalDateTime usedAt = jdbcTemplate.queryForObject(
                    "SELECT used_at FROM common_schema.admin_user_token_table WHERE email = ? AND token_type = 'RESET'",
                    LocalDateTime.class, "reset@example.com");
            assertNotNull(usedAt);
        }

        @Test
        @DisplayName("Replay same reset token → 400 (token already used)")
        void resetPassword_replayToken_returns400() throws Exception {
            seedUser("kc-replay", "replay@example.com", 0, 1, 1);
            String rawToken = "raw-reset-token-replay";
            seedToken("replay@example.com", rawToken, "RESET", null,
                    LocalDateTime.now().plusHours(1));
            String payload = String.format(
                    "{\"token\":\"%s\",\"newPassword\":\"NewPass@123\"}", rawToken);

            // First call succeeds
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk());

            // Replay → 400 (token now used)
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reset-password with unknown token → 400")
        void resetPassword_invalidToken_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"no-such-token\",\"newPassword\":\"NewPass@123\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
