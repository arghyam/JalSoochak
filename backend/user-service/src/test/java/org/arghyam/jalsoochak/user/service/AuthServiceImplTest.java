package org.arghyam.jalsoochak.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.clients.KeycloakTokenResponse;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.config.properties.FrontendProperties;
import org.arghyam.jalsoochak.user.config.properties.PasswordResetProperties;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.ActivateAccountRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.ForgotPasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.LoginRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.ResetPasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.InviteInfoResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.ResetPasswordEmailEvent;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.AccountTemporarilyLockedException;
import org.arghyam.jalsoochak.user.exceptions.CaptchaVerificationException;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UserAlreadyExistsException;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.arghyam.jalsoochak.user.repository.DataVersionRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
import org.arghyam.jalsoochak.user.service.serviceImpl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthServiceImpl - Unit Tests")
class AuthServiceImplTest {

    /**
     * A minimal JWT whose payload decodes to {"sub":"kc-uuid"}.
     * Used wherever AuthServiceImpl calls SecurityUtils.extractSubFromJwt().
     */
    private static final String FAKE_JWT =
            "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJrYy11dWlkIn0.fake_sig";

    /** JWT with staff claims: sub=kc-staff-uuid, user_type=STAFF, tenant_state_code=MP. */
    private static final String STAFF_JWT = makeJwt(
            "{\"sub\":\"kc-staff-uuid\",\"user_type\":\"STAFF\",\"tenant_state_code\":\"MP\"}");

    /** JWT with user_type but no tenant_state_code — simulates a malformed staff token. */
    private static final String STAFF_JWT_NO_TENANT = makeJwt(
            "{\"sub\":\"kc-staff-uuid\",\"user_type\":\"STAFF\"}");

    private static String makeJwt(String jsonPayload) {
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));
        return "eyJhbGciOiJSUzI1NiJ9." + encodedPayload + ".fake_sig";
    }

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private KeycloakProvider keycloakProvider;

    @Mock
    private KeycloakClient keycloakClient;

    @Mock
    private UserCommonRepository userCommonRepository;

    @Mock
    private UserTenantRepository userTenantRepository;

    @Mock
    private UserNotificationEventPublisher userNotificationEventPublisher;

    @Mock
    private UserAnalyticsEventPublisher userAnalyticsEventPublisher;

    @Mock
    private KeycloakAdminHelper keycloakAdminHelper;

    @Mock
    private PasswordResetProperties passwordResetProperties;

    @Mock
    private FrontendProperties frontendProperties;

    @Mock
    private TokenService tokenService;

    @Mock
    private MetadataDecryptionHelper metadataDecryptionHelper;

    @Mock
    private DataVersionRepository dataVersionRepository;

    @Mock
    private CaptchaVerificationService captchaVerificationService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                keycloakProvider, keycloakClient, userCommonRepository, userTenantRepository,
                userNotificationEventPublisher, userAnalyticsEventPublisher, keycloakAdminHelper, passwordResetProperties,
                frontendProperties, tokenService, new ObjectMapper(), metadataDecryptionHelper, dataVersionRepository,
                captchaVerificationService
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private AdminUserRow superUserRow() {
        return new AdminUserRow(1L, "kc-uuid", "user@example.com", "91XXXXXXXXXX", 0, 1, "SUPER_USER", AdminUserStatus.ACTIVE, 0, null);
    }

    private AdminUserRow stateAdminRow() {
        return new AdminUserRow(2L, "kc-sa", "sa@example.com", "91XXXXXXXXXX", 1, 2, "STATE_ADMIN", AdminUserStatus.ACTIVE, 0, null);
    }

    private AdminUserRow deactivatedUser() {
        return new AdminUserRow(1L, "kc-uuid", "user@example.com", "91XXXXXXXXXX", 0, 1, "SUPER_USER", AdminUserStatus.INACTIVE, 0, null);
    }

    private AdminUserTokenRow activeTokenRow(String email, String hash, String type, String metadata) {
        return new AdminUserTokenRow(1L, email, hash, type, metadata,
                Instant.now().plus(1, ChronoUnit.HOURS), null, null, Instant.now());
    }

    private AdminUserTokenRow expiredTokenRow(String email, String hash, String type) {
        return new AdminUserTokenRow(1L, email, hash, type, null,
                Instant.now().minus(1, ChronoUnit.HOURS), null, null, Instant.now());
    }

    /**
     * Returns a Keycloak token response using FAKE_JWT as the access token.
     * FAKE_JWT is a valid base64url-encoded JWT with payload {"sub":"kc-uuid"},
     * required by AuthServiceImpl.refreshToken() which calls extractSubFromJwt().
     */
    private KeycloakTokenResponse tokenResponse() {
        return new KeycloakTokenResponse(FAKE_JWT, "refresh-token", 300, 1800, "Bearer", null, null, "openid");
    }

    private TenantUserRecord activeStaffRecord() {
        return new TenantUserRecord(10L, 1, "91XXXXXXXXXX", null, 3L, "STAFF",
                "Staff User", "kc-staff-uuid", TenantUserStatus.ACTIVE.code, null);
    }

    private KeycloakTokenResponse staffTokenResponse() {
        return new KeycloakTokenResponse(STAFF_JWT, "staff-refresh-token", 300, 1800, "Bearer", null, null, "openid");
    }

    private LoginRequestDTO loginRequest(String email, String password) {
        LoginRequestDTO req = new LoginRequestDTO();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    // ── login ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login()")
    class LoginTests {

        @Test
        @DisplayName("SUPER_USER: should return enriched AuthResult with phoneNumber, no name")
        void login_superUser_returnsAuthResult() {
            when(userCommonRepository.findAdminUserByEmail("user@example.com")).thenReturn(Optional.of(superUserRow()));
            when(keycloakClient.obtainToken("user@example.com", "pass")).thenReturn(tokenResponse());

            AuthResult result = authService.login(loginRequest("user@example.com", "pass"));

            assertNotNull(result);
            assertEquals(FAKE_JWT, result.tokenResponse().getAccessToken());
            assertEquals("SUPER_USER", result.tokenResponse().getRole());
            assertEquals(1L, result.tokenResponse().getPersonId());
            assertEquals("91XXXXXXXXXX", result.tokenResponse().getPhoneNumber());
            assertNull(result.tokenResponse().getName());
            assertEquals("refresh-token", result.refreshToken());
            assertEquals(1800, result.refreshExpiresIn());
            verify(userTenantRepository, never()).findUserByEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("STATE_ADMIN: should call findUserByEmail and populate name")
        void login_stateAdmin_populatesName() {
            when(userCommonRepository.findAdminUserByEmail("sa@example.com")).thenReturn(Optional.of(stateAdminRow()));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(keycloakClient.obtainToken("sa@example.com", "pass")).thenReturn(tokenResponse());
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));

            TenantUserRecord tenantUser = new TenantUserRecord(10L, 1, "91XXXXXXXXXX", "sa@example.com", 2L, "STATE_ADMIN", "State Admin", null, null, null);
            when(userTenantRepository.findUserByEmail("tenant_mp", "sa@example.com")).thenReturn(Optional.of(tenantUser));

            AuthResult result = authService.login(loginRequest("sa@example.com", "pass"));

            assertEquals("State Admin", result.tokenResponse().getName());
            assertEquals("MP", result.tokenResponse().getTenantCode());
            verify(userTenantRepository).findUserByEmail("tenant_mp", "sa@example.com");
        }

        @Test
        @DisplayName("STATE_ADMIN: name is null when tenant user record not found")
        void login_stateAdmin_nameNullIfTenantUserMissing() {
            when(userCommonRepository.findAdminUserByEmail("sa@example.com")).thenReturn(Optional.of(stateAdminRow()));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(keycloakClient.obtainToken("sa@example.com", "pass")).thenReturn(tokenResponse());
            when(userCommonRepository.findTenantStateCodeById(1)).thenReturn(Optional.of("MP"));
            when(userTenantRepository.findUserByEmail("tenant_mp", "sa@example.com")).thenReturn(Optional.empty());

            AuthResult result = authService.login(loginRequest("sa@example.com", "pass"));

            assertNull(result.tokenResponse().getName());
        }

        @Test
        @DisplayName("CAPTCHA failure short-circuits before any DB/Keycloak work")
        void login_captchaFailure_shortCircuits() {
            doThrow(new CaptchaVerificationException("CAPTCHA verification failed"))
                    .when(captchaVerificationService).verify(any(), eq("login"));

            assertThrows(CaptchaVerificationException.class,
                    () -> authService.login(loginRequest("user@example.com", "pass")));

            verify(userCommonRepository, never()).findAdminUserByEmail(anyString());
            verify(keycloakClient, never()).obtainToken(anyString(), anyString());
        }

        @Test
        @DisplayName("Should throw InvalidCredentialsException when user not found")
        void login_userNotFound_throwsInvalidCredentials() {
            when(userCommonRepository.findAdminUserByEmail(anyString())).thenReturn(Optional.empty());

            assertThrows(InvalidCredentialsException.class,
                    () -> authService.login(loginRequest("nobody@example.com", "pass")));
        }

        @Test
        @DisplayName("Should raise AccountTemporarilyLockedException when a 401 hides a brute-force lockout")
        void login_lockedAccount_throwsTooManyRequests() {
            when(userCommonRepository.findAdminUserByEmail("user@example.com")).thenReturn(Optional.of(superUserRow()));
            when(keycloakClient.obtainToken("user@example.com", "wrong"))
                    .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username, password, or refresh token"));
            when(keycloakAdminHelper.isTemporarilyLockedByBruteForce("kc-uuid")).thenReturn(true);

            assertThrows(AccountTemporarilyLockedException.class,
                    () -> authService.login(loginRequest("user@example.com", "wrong")));
        }

        @Test
        @DisplayName("Wrong password on an unlocked account is normalised to the generic invalid-credentials error")
        void login_wrongPassword_notLocked_normalisesToInvalidCredentials() {
            when(userCommonRepository.findAdminUserByEmail("user@example.com")).thenReturn(Optional.of(superUserRow()));
            when(keycloakClient.obtainToken("user@example.com", "wrong"))
                    .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username, password, or refresh token"));
            when(keycloakAdminHelper.isTemporarilyLockedByBruteForce("kc-uuid")).thenReturn(false);

            InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                    () -> authService.login(loginRequest("user@example.com", "wrong")));

            // Must match the unknown-account message verbatim so responses are indistinguishable.
            assertEquals("Invalid credentials", ex.getMessage());
        }

        @Test
        @DisplayName("Should throw AccountDeactivatedException when account is deactivated")
        void login_deactivated_throwsAccountDeactivated() {
            when(userCommonRepository.findAdminUserByEmail("user@example.com")).thenReturn(Optional.of(deactivatedUser()));

            assertThrows(AccountDeactivatedException.class,
                    () -> authService.login(loginRequest("user@example.com", "pass")));

            verify(keycloakClient, never()).obtainToken(anyString(), anyString());
        }

        @Test
        @DisplayName("STATE_ADMIN: should throw AccountDeactivatedException when tenant does not exist")
        void login_stateAdmin_tenantNotFound_throwsAccountDeactivated() {
            when(userCommonRepository.findAdminUserByEmail("sa@example.com")).thenReturn(Optional.of(stateAdminRow()));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.empty());

            assertThrows(AccountDeactivatedException.class,
                    () -> authService.login(loginRequest("sa@example.com", "pass")));

            verify(keycloakClient, never()).obtainToken(anyString(), anyString());
        }
    }

    // ── refreshToken ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refreshToken()")
    class RefreshTokenTests {

        @Test
        @DisplayName("Should return AuthResult with new tokens on valid refresh token")
        void refreshToken_success() {
            // FAKE_JWT payload decodes to {"sub":"kc-uuid"} — extractSubFromJwt() returns "kc-uuid"
            when(keycloakClient.refreshToken("valid-refresh")).thenReturn(tokenResponse());
            when(userCommonRepository.findAdminUserByUuid("kc-uuid")).thenReturn(Optional.of(superUserRow()));

            AuthResult result = authService.refreshToken("valid-refresh");

            assertEquals(FAKE_JWT, result.tokenResponse().getAccessToken());
            assertEquals("refresh-token", result.refreshToken());
            assertEquals(1800, result.refreshExpiresIn());
        }

        @Test
        @DisplayName("Should throw AccountDeactivatedException when user is deactivated")
        void refreshToken_deactivatedUser_throwsAccountDeactivated() {
            when(keycloakClient.refreshToken("valid-refresh")).thenReturn(tokenResponse());
            when(userCommonRepository.findAdminUserByUuid("kc-uuid")).thenReturn(Optional.of(deactivatedUser()));

            assertThrows(AccountDeactivatedException.class, () -> authService.refreshToken("valid-refresh"));
        }

        @Test
        @DisplayName("Should throw BadRequestException when refresh token is blank")
        void refreshToken_blank_throwsBadRequest() {
            assertThrows(BadRequestException.class, () -> authService.refreshToken(""));
            assertThrows(BadRequestException.class, () -> authService.refreshToken(null));
            verify(keycloakClient, never()).refreshToken(anyString());
        }
    }

    // ── refreshToken (staff path) ─────────────────────────────────────────────────

    @Nested
    @DisplayName("refreshToken() – staff user path")
    class RefreshTokenStaffTests {

        @Test
        @DisplayName("Active staff user: should return AuthResult with staff fields, skip admin lookup")
        void refreshToken_staff_success() {
            when(keycloakClient.refreshToken("staff-refresh")).thenReturn(staffTokenResponse());
            when(userTenantRepository.findUserByKeycloakUuid("tenant_mp", "kc-staff-uuid"))
                    .thenReturn(Optional.of(activeStaffRecord()));
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE

            AuthResult result = authService.refreshToken("staff-refresh");

            assertEquals(STAFF_JWT, result.tokenResponse().getAccessToken());
            assertEquals("staff-refresh-token", result.refreshToken());
            assertEquals(1800, result.refreshExpiresIn());
            assertEquals("STAFF", result.tokenResponse().getRole());
            assertEquals("MP", result.tokenResponse().getTenantCode());
            assertEquals(10L, result.tokenResponse().getPersonId());
            assertEquals("Staff User", result.tokenResponse().getName());
            assertEquals("91XXXXXXXXXX", result.tokenResponse().getPhoneNumber());
            verify(userCommonRepository, never()).findAdminUserByUuid(anyString());
        }

        @Test
        @DisplayName("Staff user not found in tenant schema: should throw ResourceNotFoundException")
        void refreshToken_staffNotFound_throwsResourceNotFound() {
            when(keycloakClient.refreshToken("staff-refresh")).thenReturn(staffTokenResponse());
            when(userTenantRepository.findUserByKeycloakUuid("tenant_mp", "kc-staff-uuid"))
                    .thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> authService.refreshToken("staff-refresh"));
            verify(userCommonRepository, never()).findAdminUserByUuid(anyString());
        }

        @Test
        @DisplayName("Deactivated staff user: should throw AccountDeactivatedException")
        void refreshToken_staffDeactivated_throwsAccountDeactivated() {
            TenantUserRecord inactiveStaff = new TenantUserRecord(10L, 1, "91XXXXXXXXXX", null, 3L, "STAFF",
                    "Staff User", "kc-staff-uuid", TenantUserStatus.INACTIVE.code, null);
            when(keycloakClient.refreshToken("staff-refresh")).thenReturn(staffTokenResponse());
            when(userTenantRepository.findUserByKeycloakUuid("tenant_mp", "kc-staff-uuid"))
                    .thenReturn(Optional.of(inactiveStaff));

            assertThrows(AccountDeactivatedException.class, () -> authService.refreshToken("staff-refresh"));
            verify(userCommonRepository, never()).findAdminUserByUuid(anyString());
        }

        @Test
        @DisplayName("Tenant not found for staff: should throw AccountDeactivatedException")
        void refreshToken_staffTenantNotFound_throwsAccountDeactivated() {
            when(keycloakClient.refreshToken("staff-refresh")).thenReturn(staffTokenResponse());
            when(userTenantRepository.findUserByKeycloakUuid("tenant_mp", "kc-staff-uuid"))
                    .thenReturn(Optional.of(activeStaffRecord()));
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.empty());

            assertThrows(AccountDeactivatedException.class, () -> authService.refreshToken("staff-refresh"));
        }

        @Test
        @DisplayName("Missing tenant_state_code in JWT: should throw ResourceNotFoundException before DB lookup")
        void refreshToken_staffMissingTenantCode_throwsResourceNotFound() {
            KeycloakTokenResponse noTenantToken = new KeycloakTokenResponse(
                    STAFF_JWT_NO_TENANT, "staff-refresh-token", 300, 1800, "Bearer", null, null, "openid");
            when(keycloakClient.refreshToken("staff-refresh")).thenReturn(noTenantToken);

            assertThrows(ResourceNotFoundException.class, () -> authService.refreshToken("staff-refresh"));
            verify(userTenantRepository, never()).findUserByKeycloakUuid(anyString(), anyString());
            verify(userCommonRepository, never()).findAdminUserByUuid(anyString());
        }
    }

    // ── getInviteInfo ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getInviteInfo()")
    class GetInviteInfoTests {

        @Test
        @DisplayName("Should return invite info from a valid opaque token")
        void getInviteInfo_validToken_returnsInfo() {
            String rawToken = "raw-invite-token";
            String hash = "invite-hash";
            String metadata = "{\"role\":\"STATE_ADMIN\",\"tenantName\":\"Madhya Pradesh\",\"tenantCode\":\"MP\",\"firstName\":\"<enc-john>\",\"lastName\":\"<enc-doe>\"}";
            when(tokenService.hash(rawToken)).thenReturn(hash);
            when(userCommonRepository.findActiveTokenByHash(hash)).thenReturn(Optional.of(
                    activeTokenRow("invited@example.com", hash, "INVITE", metadata)));
            when(metadataDecryptionHelper.parseAndDecrypt(metadata, "firstName")).thenReturn("John");
            when(metadataDecryptionHelper.parseAndDecrypt(metadata, "lastName")).thenReturn("Doe");
            when(userCommonRepository.existsActiveAdminUserByEmail("invited@example.com")).thenReturn(false);
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            // phoneNumber is fetched from the PENDING user record
            AdminUserRow pendingUser = new AdminUserRow(5L, "placeholder-uuid", "invited@example.com", "9112345678", 1, 2, "STATE_ADMIN", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("invited@example.com")).thenReturn(Optional.of(pendingUser));

            InviteInfoResponseDTO info = authService.getInviteInfo(rawToken);

            assertEquals("invited@example.com", info.getEmail());
            assertEquals("STATE_ADMIN", info.getRole());
            assertEquals("Madhya Pradesh", info.getTenantName());
            assertEquals("John", info.getFirstName());
            assertEquals("Doe", info.getLastName());
            assertEquals("9112345678", info.getPhoneNumber());
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN: returns invite info with tenant name")
        void getInviteInfo_superStateAdmin_returnsInfo() {
            String rawToken = "ssa-invite-token";
            String hash = "ssa-invite-hash";
            String metadata = "{\"role\":\"SUPER_STATE_ADMIN\",\"tenantName\":\"Madhya Pradesh\",\"tenantCode\":\"MP\",\"firstName\":\"<enc-hybrid>\",\"lastName\":\"<enc-admin>\"}";
            when(tokenService.hash(rawToken)).thenReturn(hash);
            when(userCommonRepository.findActiveTokenByHash(hash)).thenReturn(Optional.of(
                    activeTokenRow("ssa@example.com", hash, "INVITE", metadata)));
            when(metadataDecryptionHelper.parseAndDecrypt(metadata, "firstName")).thenReturn("Hybrid");
            when(metadataDecryptionHelper.parseAndDecrypt(metadata, "lastName")).thenReturn("Admin");
            when(userCommonRepository.existsActiveAdminUserByEmail("ssa@example.com")).thenReturn(false);
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            AdminUserRow pendingUser = new AdminUserRow(6L, "placeholder-uuid", "ssa@example.com", "91XXXXXXXXXX", 1, 4, "SUPER_STATE_ADMIN", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("ssa@example.com")).thenReturn(Optional.of(pendingUser));

            InviteInfoResponseDTO info = authService.getInviteInfo(rawToken);

            assertEquals("ssa@example.com", info.getEmail());
            assertEquals("SUPER_STATE_ADMIN", info.getRole());
            assertEquals("Madhya Pradesh", info.getTenantName());
            assertEquals("Hybrid", info.getFirstName());
            assertEquals("Admin", info.getLastName());
        }

        @Test
        @DisplayName("Should throw BadRequestException when token not found in DB")
        void getInviteInfo_unknownToken_throwsBadRequest() {
            when(tokenService.hash(anyString())).thenReturn("unknown-hash");
            when(userCommonRepository.findActiveTokenByHash("unknown-hash")).thenReturn(Optional.empty());

            assertThrows(BadRequestException.class, () -> authService.getInviteInfo("unknown-token"));
        }

        @Test
        @DisplayName("STATE_ADMIN: throws ForbiddenAccessException when tenant is archived")
        void getInviteInfo_archivedTenant_throwsForbiddenAccess() {
            String hash = "invite-hash";
            when(tokenService.hash("raw")).thenReturn(hash);
            when(userCommonRepository.findActiveTokenByHash(hash)).thenReturn(Optional.of(
                    activeTokenRow("invited@example.com", hash, "INVITE",
                            "{\"role\":\"STATE_ADMIN\",\"tenantCode\":\"MP\"}")));
            when(userCommonRepository.existsActiveAdminUserByEmail("invited@example.com")).thenReturn(false);
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(6)); // ARCHIVED

            assertThrows(ForbiddenAccessException.class, () -> authService.getInviteInfo("raw"));
        }

        @Test
        @DisplayName("Should throw UserAlreadyExistsException when account already exists")
        void getInviteInfo_accountExists_throwsUserAlreadyExists() {
            String hash = "existing-hash";
            when(tokenService.hash("raw")).thenReturn(hash);
            when(userCommonRepository.findActiveTokenByHash(hash)).thenReturn(Optional.of(
                    activeTokenRow("existing@example.com", hash, "INVITE", "{\"role\":\"SUPER_USER\"}")));
            // Actual code uses existsActiveAdminUserByEmail
            when(userCommonRepository.existsActiveAdminUserByEmail("existing@example.com")).thenReturn(true);

            assertThrows(UserAlreadyExistsException.class, () -> authService.getInviteInfo("raw"));
        }
    }

    // ── forgotPassword ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("forgotPassword()")
    class ForgotPasswordTests {

        @Test
        @DisplayName("Should return silently (OWASP) when email is not registered")
        void forgotPassword_userNotFound_returnsQuietly() {
            when(userCommonRepository.findAdminUserByEmail("nobody@example.com")).thenReturn(Optional.empty());

            ForgotPasswordRequestDTO req = new ForgotPasswordRequestDTO();
            req.setEmail("nobody@example.com");

            authService.forgotPassword(req); // no exception

            verify(userNotificationEventPublisher, never()).publishResetPasswordEmailAfterCommit(any(ResetPasswordEmailEvent.class));
        }

        @Test
        @DisplayName("Should generate token, insert in DB, and send reset email when user is registered")
        void forgotPassword_userFound_sendsEmail() {
            when(userCommonRepository.findAdminUserByEmail("user@example.com")).thenReturn(Optional.of(superUserRow()));
            when(tokenService.generateRawToken()).thenReturn("raw-reset-token");
            when(tokenService.hash("raw-reset-token")).thenReturn("reset-hash");
            when(passwordResetProperties.expiryMinutes()).thenReturn(30);
            when(frontendProperties.baseUrl()).thenReturn("http://localhost:3000");
            when(frontendProperties.resetPath()).thenReturn("/reset-password");
            doNothing().when(userCommonRepository).insertToken(
                    eq("user@example.com"), eq("reset-hash"), eq("RESET"), eq(null), any(), eq(null));

            ForgotPasswordRequestDTO req = new ForgotPasswordRequestDTO();
            req.setEmail("user@example.com");

            authService.forgotPassword(req);

            verify(userCommonRepository).insertToken(
                    eq("user@example.com"), eq("reset-hash"), eq("RESET"), eq(null), any(), eq(null));
            verify(userNotificationEventPublisher).publishResetPasswordEmailAfterCommit(any(ResetPasswordEmailEvent.class));
        }
    }

    // ── resetPassword ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resetPassword()")
    class ResetPasswordTests {

        @Test
        @DisplayName("Should reset password when token is valid and has not been used")
        void resetPassword_success() {
            String rawToken = "raw-reset-token";
            String hash = "reset-hash";
            when(tokenService.hash(rawToken)).thenReturn(hash);
            AdminUserTokenRow tokenRow = activeTokenRow("user@example.com", hash, "RESET", null);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "RESET")).thenReturn(Optional.of(tokenRow));

            AdminUserRow user = new AdminUserRow(1L, "kc-uuid", "user@example.com", "91XXXXXXXXXX", 0, 1, "SUPER_USER", AdminUserStatus.ACTIVE, 0, null);
            when(userCommonRepository.findAdminUserByEmail("user@example.com")).thenReturn(Optional.of(user));

            ResetPasswordRequestDTO req = new ResetPasswordRequestDTO();
            req.setToken(rawToken);
            req.setNewPassword("NewPass@123");

            authService.resetPassword(req);

            verify(userCommonRepository).consumeActiveTokenOfType(hash, "RESET");
        }

        @Test
        @DisplayName("Should throw BadRequestException when token not found (not active)")
        void resetPassword_tokenNotFound_throwsBadRequest() {
            when(tokenService.hash(anyString())).thenReturn("no-hash");
            when(userCommonRepository.consumeActiveTokenOfType("no-hash", "RESET")).thenReturn(Optional.empty());

            ResetPasswordRequestDTO req = new ResetPasswordRequestDTO();
            req.setToken("no-token");
            req.setNewPassword("NewPass@123");

            assertThrows(BadRequestException.class, () -> authService.resetPassword(req));
        }

        @Test
        @DisplayName("Should throw BadRequestException when token is expired (consumeActiveToken returns empty)")
        void resetPassword_tokenExpired_throwsBadRequest() {
            String hash = "expired-hash";
            when(tokenService.hash("expired")).thenReturn(hash);
            // consumeActiveTokenOfType excludes expired/used/type-mismatched tokens in the SQL WHERE clause
            when(userCommonRepository.consumeActiveTokenOfType(hash, "RESET")).thenReturn(Optional.empty());

            ResetPasswordRequestDTO req = new ResetPasswordRequestDTO();
            req.setToken("expired");
            req.setNewPassword("NewPass@123");

            assertThrows(BadRequestException.class, () -> authService.resetPassword(req));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user not found by email in token")
        void resetPassword_userNotFound_throwsResourceNotFound() {
            String hash = "hash-ghost";
            when(tokenService.hash("ghost")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "RESET")).thenReturn(Optional.of(
                    activeTokenRow("ghost@example.com", hash, "RESET", null)));
            when(userCommonRepository.findAdminUserByEmail("ghost@example.com")).thenReturn(Optional.empty());

            ResetPasswordRequestDTO req = new ResetPasswordRequestDTO();
            req.setToken("ghost");
            req.setNewPassword("NewPass@123");

            assertThrows(ResourceNotFoundException.class, () -> authService.resetPassword(req));
        }
    }

    // ── activateAccount ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("activateAccount()")
    class ActivateAccountTests {

        @Test
        @DisplayName("Should throw BadRequestException when invite token not found")
        void activateAccount_invalidToken_throwsBadRequest() {
            when(tokenService.hash(anyString())).thenReturn("bad-hash");
            when(userCommonRepository.consumeActiveTokenOfType("bad-hash", "INVITE")).thenReturn(Optional.empty());

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("bad-token");
            req.setFirstName("Test");
            req.setLastName("User");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            assertThrows(BadRequestException.class, () -> authService.activateAccount(req));
        }

        @Test
        @DisplayName("Should throw UserAlreadyExistsException when user record is already active (status != 2)")
        void activateAccount_emailAlreadyExists_throwsUserAlreadyExists() {
            String hash = "dup-hash";
            when(tokenService.hash("dup-token")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")).thenReturn(Optional.of(
                    activeTokenRow("existing@example.com", hash, "INVITE", "{\"role\":\"SUPER_USER\"}")));
            // activateAccount finds the user record and checks status; status=1 (active) => already registered
            AdminUserRow activeUser = new AdminUserRow(5L, "kc-dup", "existing@example.com", "91XXXXXXXXXX", 0, 1, "SUPER_USER", AdminUserStatus.ACTIVE, 0, null);
            when(userCommonRepository.findAdminUserByEmail("existing@example.com")).thenReturn(Optional.of(activeUser));

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("dup-token");
            req.setFirstName("Test");
            req.setLastName("User");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            assertThrows(UserAlreadyExistsException.class, () -> authService.activateAccount(req));
        }

        @Test
        @DisplayName("Should create SUPER_USER atomically (consumeActiveTokenOfType) and return AuthResult")
        void activateAccount_superUser_returnsAuthResult() {
            String hash = "su-hash";
            when(tokenService.hash("su-token")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")).thenReturn(Optional.of(
                    activeTokenRow("newsuper@example.com", hash, "INVITE", "{\"role\":\"SUPER_USER\"}")));

            // Pending user record (status=2) created at invite time
            AdminUserRow pendingUser = new AdminUserRow(10L, "pending-uuid", "newsuper@example.com", "", 0, 1, "SUPER_USER", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("newsuper@example.com")).thenReturn(Optional.of(pendingUser));

            when(keycloakProvider.getRealm()).thenReturn("test-realm");
            jakarta.ws.rs.core.Response createResp = jakarta.ws.rs.core.Response
                    .created(java.net.URI.create("http://keycloak/users/new-kc-id"))
                    .build();
            when(keycloakProvider.getAdminInstance().realm("test-realm").users().create(any()))
                    .thenReturn(createResp);
            doNothing().when(keycloakAdminHelper).assignRoleToUser(anyString(), anyString());
            doNothing().when(userCommonRepository).activatePendingAdminUser(eq(10L), anyString(), anyString());
            when(keycloakClient.obtainToken(anyString(), anyString())).thenReturn(tokenResponse());

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("su-token");
            req.setFirstName("New");
            req.setLastName("Super");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            AuthResult result = authService.activateAccount(req);

            assertNotNull(result);
            assertEquals(FAKE_JWT, result.tokenResponse().getAccessToken());
            assertNull(result.tokenResponse().getName());
            assertEquals("91XXXXXXXXXX", result.tokenResponse().getPhoneNumber());
            verify(userCommonRepository).consumeActiveTokenOfType(hash, "INVITE");
            verify(userCommonRepository).activatePendingAdminUser(eq(10L), anyString(), anyString());
            verify(dataVersionRepository, never()).bump(anyString(), any());
        }

        @Test
        @DisplayName("Throws BadRequestException when invite token role does not match admin level")
        void activateAccount_roleMismatch_throwsBadRequest() {
            String hash = "mismatch-hash";
            when(tokenService.hash("mismatch-token")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")).thenReturn(Optional.of(
                    activeTokenRow("mismatch@example.com", hash, "INVITE",
                            "{\"role\":\"STATE_ADMIN\",\"tenantCode\":\"MP\"}")));
            // Pending user is SUPER_USER in DB, but token says STATE_ADMIN — mismatch expected
            AdminUserRow pendingUser = new AdminUserRow(25L, "pending-mismatch-uuid", "mismatch@example.com", "", 0, 1, "SUPER_USER", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("mismatch@example.com")).thenReturn(Optional.of(pendingUser));
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("mismatch-token");
            req.setFirstName("Mismatch");
            req.setLastName("User");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            assertThrows(BadRequestException.class, () -> authService.activateAccount(req));
        }

        @Test
        @DisplayName("STATE_ADMIN: throws ForbiddenAccessException when tenant is archived")
        void activateAccount_stateAdmin_archivedTenant_throwsForbiddenAccess() {
            String hash = "sa-hash";
            when(tokenService.hash("sa-token")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")).thenReturn(Optional.of(
                    activeTokenRow("newsa@example.com", hash, "INVITE",
                            "{\"role\":\"STATE_ADMIN\",\"tenantCode\":\"MP\"}")));
            AdminUserRow pendingUser = new AdminUserRow(20L, "pending-sa-uuid", "newsa@example.com", "", 1, 2, "STATE_ADMIN", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("newsa@example.com")).thenReturn(Optional.of(pendingUser));
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(6)); // ARCHIVED

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("sa-token");
            req.setFirstName("State");
            req.setLastName("Admin");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            assertThrows(ForbiddenAccessException.class, () -> authService.activateAccount(req));
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN: activates successfully with tenant row creation")
        void activateAccount_superStateAdmin_returnsAuthResult() {
            String hash = "ssa-hash";
            when(tokenService.hash("ssa-token")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")).thenReturn(Optional.of(
                    activeTokenRow("newssa@example.com", hash, "INVITE",
                            "{\"role\":\"SUPER_STATE_ADMIN\",\"tenantCode\":\"MP\"}")));

            AdminUserRow pendingUser = new AdminUserRow(30L, "pending-ssa-uuid", "newssa@example.com", "", 1, 4, "SUPER_STATE_ADMIN", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("newssa@example.com")).thenReturn(Optional.of(pendingUser));

            when(keycloakProvider.getRealm()).thenReturn("test-realm");
            jakarta.ws.rs.core.Response createResp = jakarta.ws.rs.core.Response
                    .created(java.net.URI.create("http://keycloak/users/ssa-kc-id"))
                    .build();
            when(keycloakProvider.getAdminInstance().realm("test-realm").users().create(any()))
                    .thenReturn(createResp);
            doNothing().when(keycloakAdminHelper).assignRoleToUser(anyString(), anyString());
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            doNothing().when(userCommonRepository).activatePendingAdminUser(eq(30L), anyString(), anyString());
            AdminUserRow activatedUser = new AdminUserRow(30L, "ssa-kc-id", "newssa@example.com", "", 1, 4, "SUPER_STATE_ADMIN", AdminUserStatus.ACTIVE, 0, null);
            when(userCommonRepository.findAdminUserByUuid("ssa-kc-id")).thenReturn(Optional.of(activatedUser));
            when(userTenantRepository.createUser(anyString(), anyString(), any(), anyString(),
                    anyString(), any(), anyString(), anyString(), any())).thenReturn(1L);
            when(keycloakClient.obtainToken(anyString(), anyString())).thenReturn(tokenResponse());

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("ssa-token");
            req.setFirstName("Hybrid");
            req.setLastName("Admin");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            AuthResult result = authService.activateAccount(req);

            assertNotNull(result);
            assertEquals("MP", result.tokenResponse().getTenantCode());
            assertEquals("Hybrid Admin", result.tokenResponse().getName());
            verify(userCommonRepository).activatePendingAdminUser(eq(30L), anyString(), anyString());
            verify(userTenantRepository).createUser(eq("tenant_mp"), anyString(), any(), eq("Hybrid Admin"),
                    eq("newssa@example.com"), any(), anyString(), anyString(), any());
            verify(dataVersionRepository).bump("tenant_mp", ResourceType.STAFF_USERS);
        }

        @Test
        @DisplayName("STATE_ADMIN: creates dual DB rows, name populated in response")
        void activateAccount_stateAdmin_returnsAuthResult() {
            String hash = "sa-hash";
            when(tokenService.hash("sa-token")).thenReturn(hash);
            when(userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")).thenReturn(Optional.of(
                    activeTokenRow("newsa@example.com", hash, "INVITE",
                            "{\"role\":\"STATE_ADMIN\",\"tenantCode\":\"MP\"}")));

            AdminUserRow pendingUser = new AdminUserRow(20L, "pending-sa-uuid", "newsa@example.com", "", 1, 2, "STATE_ADMIN", AdminUserStatus.PENDING, 0, null);
            when(userCommonRepository.findAdminUserByEmail("newsa@example.com")).thenReturn(Optional.of(pendingUser));

            when(keycloakProvider.getRealm()).thenReturn("test-realm");
            jakarta.ws.rs.core.Response createResp = jakarta.ws.rs.core.Response
                    .created(java.net.URI.create("http://keycloak/users/sa-kc-id"))
                    .build();
            when(keycloakProvider.getAdminInstance().realm("test-realm").users().create(any()))
                    .thenReturn(createResp);
            doNothing().when(keycloakAdminHelper).assignRoleToUser(anyString(), anyString());
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            doNothing().when(userCommonRepository).activatePendingAdminUser(eq(20L), anyString(), anyString());
            AdminUserRow activatedUser = new AdminUserRow(20L, "sa-kc-id", "newsa@example.com", "", 1, 3, "STATE_ADMIN", AdminUserStatus.ACTIVE, 0, null);
            when(userCommonRepository.findAdminUserByUuid("sa-kc-id")).thenReturn(Optional.of(activatedUser));
            when(userTenantRepository.createUser(anyString(), anyString(), any(), anyString(),
                    anyString(), any(), anyString(), anyString(), any())).thenReturn(1L);
            when(keycloakClient.obtainToken(anyString(), anyString())).thenReturn(tokenResponse());

            ActivateAccountRequestDTO req = new ActivateAccountRequestDTO();
            req.setInviteToken("sa-token");
            req.setFirstName("State");
            req.setLastName("Admin");
            req.setPassword("Pass@123");
            req.setPhoneNumber("91XXXXXXXXXX");

            AuthResult result = authService.activateAccount(req);

            assertNotNull(result);
            assertEquals("State Admin", result.tokenResponse().getName());
            assertEquals("MP", result.tokenResponse().getTenantCode());
            verify(userCommonRepository).activatePendingAdminUser(eq(20L), anyString(), anyString());
            verify(userTenantRepository).createUser(eq("tenant_mp"), anyString(), any(), eq("State Admin"),
                    eq("newsa@example.com"), any(), anyString(), anyString(), any());
            verify(dataVersionRepository).bump("tenant_mp", ResourceType.STAFF_USERS);
        }
    }
}
