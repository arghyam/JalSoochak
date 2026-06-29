package org.arghyam.jalsoochak.user.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UsersResource;
import org.mockito.Answers;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.client.WireMock;

import jakarta.ws.rs.core.Response;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@AutoConfigureWireMock(port = 0)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Staff OTP Auth Integration Tests")
class StaffAuthControllerIntegrationTest {

    private static final String KEYCLOAK_TOKEN_RESPONSE = """
            {"access_token":"staff-at","refresh_token":"staff-rt","expires_in":300,
             "refresh_expires_in":1800,"token_type":"Bearer","scope":"openid"}
            """;

    @SuppressWarnings("resource")
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

    @MockBean KeycloakProvider keycloakProvider;
    @MockBean
    UserNotificationEventPublisher userNotificationEventPublisher;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PiiEncryptionService piiEncryptionService;

    private long staffUserId;

    @BeforeEach
    void setUp() {
        WireMock.reset();
        jdbcTemplate.execute("DELETE FROM common_schema.otp_table");
        jdbcTemplate.execute("DELETE FROM tenant_mp.user_table");

        Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
        lenient().when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
        lenient().when(keycloakProvider.getRealm()).thenReturn("jalsoochak-realm");

        // Seed an active staff user (user_type=3 is SECTION_OFFICER)
        Number id = jdbcTemplate.queryForObject("""
                INSERT INTO tenant_mp.user_table
                    (tenant_id, user_type, title, phone_number, phone_number_hash,
                     password, status, whatsapp_connection_id)
                VALUES (1, 3, ?, ?, ?, 'CSV_ONBOARDED', 1, 99001)
                RETURNING id
                """, Number.class,
                piiEncryptionService.encrypt("Test Officer"),
                piiEncryptionService.encrypt("919876543210"),
                piiEncryptionService.hmac("919876543210"));
        staffUserId = id.longValue();
        clearInvocations(keycloakProvider);
    }

    @Nested
    @DisplayName("POST /api/v1/auth/staff/otp")
    class RequestOtp {

        @Test
        @DisplayName("returns 200 with otpLength for a valid registered phone")
        void returns200ForRegisteredPhone() throws Exception {
            mockMvc.perform(post("/api/v1/auth/staff/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"919876543210","tenantCode":"MP"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is(200)))
                    .andExpect(jsonPath("$.data.otpLength", is(6))); // Verify OTP length is returned

            // OTP row must be created
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.otp_table WHERE user_id = ? AND used_at IS NULL",
                    Integer.class, staffUserId);
            assertThat(count).isNotNull().isEqualTo(1);
        }

        @Test
        @DisplayName("returns 404 with a re-check message for an unregistered phone")
        void returns404ForUnknownPhone() throws Exception {
            mockMvc.perform(post("/api/v1/auth/staff/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"911111111111","tenantCode":"MP"}
                                    """))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", containsString("couldn't find an account")));
        }

        @Test
        @DisplayName("returns 400 for missing phoneNumber")
        void returns400ForMissingPhone() throws Exception {
            mockMvc.perform(post("/api/v1/auth/staff/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"tenantCode":"MP"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when phone belongs to SUPER_USER (intentional anti-enumeration break)")
        void returns400ForSuperUser() throws Exception {
            // Insert a SUPER_USER (user_type=1)
            long superUserId = jdbcTemplate.queryForObject("""
                    INSERT INTO tenant_mp.user_table
                        (tenant_id, user_type, title, phone_number, phone_number_hash,
                         password, status, whatsapp_connection_id)
                    VALUES (1, 1, ?, ?, ?, 'CSV_ONBOARDED', 1, 99001)
                    RETURNING id
                    """, Long.class,
                    piiEncryptionService.encrypt("Super Admin"),
                    piiEncryptionService.encrypt("919999999999"),
                    piiEncryptionService.hmac("919999999999"));

            mockMvc.perform(post("/api/v1/auth/staff/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"919999999999","tenantCode":"MP"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("staff users")))
                    .andExpect(jsonPath("$.message", containsString("email and password")));

            // No OTP should be created
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.otp_table WHERE user_id = ?",
                    Integer.class, superUserId);
            assertThat(count).isZero();
        }

        @Test
        @DisplayName("returns 400 when phone belongs to STATE_ADMIN (intentional anti-enumeration break)")
        void returns400ForStateAdmin() throws Exception {
            // Insert a STATE_ADMIN (user_type=2)
            long adminUserId = jdbcTemplate.queryForObject("""
                    INSERT INTO tenant_mp.user_table
                        (tenant_id, user_type, title, phone_number, phone_number_hash,
                         password, status, whatsapp_connection_id)
                    VALUES (1, 2, ?, ?, ?, 'CSV_ONBOARDED', 1, 99001)
                    RETURNING id
                    """, Long.class,
                    piiEncryptionService.encrypt("State Admin"),
                    piiEncryptionService.encrypt("918888888888"),
                    piiEncryptionService.hmac("918888888888"));

            mockMvc.perform(post("/api/v1/auth/staff/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"918888888888","tenantCode":"MP"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("staff users")))
                    .andExpect(jsonPath("$.message", containsString("email and password")));

            // No OTP should be created
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.otp_table WHERE user_id = ?",
                    Integer.class, adminUserId);
            assertThat(count).isZero();
        }

        @Test
        @DisplayName("returns 200 silently when phone belongs to PUMP_OPERATOR (anti-enumeration preserved)")
        void returns200ForPumpOperator() throws Exception {
            // Insert a PUMP_OPERATOR (user_type=4)
            long pumpOpId = jdbcTemplate.queryForObject("""
                    INSERT INTO tenant_mp.user_table
                        (tenant_id, user_type, title, phone_number, phone_number_hash,
                         password, status, whatsapp_connection_id)
                    VALUES (1, 4, ?, ?, ?, 'CSV_ONBOARDED', 1, 99001)
                    RETURNING id
                    """, Long.class,
                    piiEncryptionService.encrypt("Pump Operator"),
                    piiEncryptionService.encrypt("917777777777"),
                    piiEncryptionService.hmac("917777777777"));

            mockMvc.perform(post("/api/v1/auth/staff/otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"917777777777","tenantCode":"MP"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.otpLength", is(6)));

            // No OTP should be created (silent failure)
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.otp_table WHERE user_id = ?",
                    Integer.class, pumpOpId);
            assertThat(count).isZero();
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/staff/otp/verify")
    class VerifyOtp {

        private String seedOtp(String rawOtp) {
            jdbcTemplate.update("""
                    INSERT INTO common_schema.otp_table
                        (otp, tenant_id, user_id, otp_type, expires_at)
                    VALUES (?, 1, ?, 'LOGIN', NOW() + INTERVAL '10 minutes')
                    """, piiEncryptionService.encrypt(rawOtp), staffUserId);
            return rawOtp;
        }

        @Test
        @DisplayName("returns 200 with access token on valid OTP")
        void returns200WithTokenOnValidOtp() throws Exception {
            String otp = seedOtp("123456");

            // Mock Keycloak admin for lazy provisioning
            Keycloak mockAdmin = mock(Keycloak.class, Answers.RETURNS_DEEP_STUBS);
            when(keycloakProvider.getAdminInstance()).thenReturn(mockAdmin);
            when(keycloakProvider.getRealm()).thenReturn("jalsoochak-realm");

            UsersResource usersResource = mock(UsersResource.class, Answers.RETURNS_DEEP_STUBS);
            when(mockAdmin.realm(anyString()).users()).thenReturn(usersResource);

            Response createResponse = mock(Response.class);
            when(createResponse.getStatus()).thenReturn(201);
            when(createResponse.getLocation()).thenReturn(URI.create("http://keycloak/users/00000000-0000-0000-0000-000000000001"));
            // Note: Mock Response doesn't require close(); production code handles real Response via try-with-resources
            when(usersResource.create(any())).thenReturn(createResponse);

            // WireMock: Keycloak token endpoint
            stubFor(WireMock.post(urlEqualTo("/realms/jalsoochak-realm/protocol/openid-connect/token"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(KEYCLOAK_TOKEN_RESPONSE)));

            mockMvc.perform(post("/api/v1/auth/staff/otp/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"919876543210","tenantCode":"MP","otp":"%s"}
                                    """.formatted(otp)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.access_token", is("staff-at")))
                    .andExpect(jsonPath("$.data.tenant_code", is("MP")));

            // OTP row must be marked consumed (used_at IS NOT NULL)
            Integer unconsumed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.otp_table WHERE user_id = ? AND used_at IS NULL",
                    Integer.class, staffUserId);
            assertThat(unconsumed).isNotNull().isZero();

            Integer consumed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.otp_table WHERE user_id = ? AND used_at IS NOT NULL",
                    Integer.class, staffUserId);
            assertThat(consumed).isNotNull().isEqualTo(1);
        }

        @Test
        @DisplayName("returns 400 for wrong OTP")
        void returns400ForWrongOtp() throws Exception {
            seedOtp("123456");

            mockMvc.perform(post("/api/v1/auth/staff/otp/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"919876543210","tenantCode":"MP","otp":"999999"}
                                    """))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(keycloakProvider);
        }

        @Test
        @DisplayName("returns 400 when no OTP was requested")
        void returns400WhenNoOtpExists() throws Exception {
            mockMvc.perform(post("/api/v1/auth/staff/otp/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phoneNumber":"919876543210","tenantCode":"MP","otp":"123456"}
                                    """))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(keycloakProvider);
        }
    }
}
