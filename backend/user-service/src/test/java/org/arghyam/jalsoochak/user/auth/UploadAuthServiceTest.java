package org.arghyam.jalsoochak.user.auth;

import org.arghyam.jalsoochak.user.repository.UserUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@ExtendWith(MockitoExtension.class)
@DisplayName("UploadAuthService")
class UploadAuthServiceTest {

    @Mock private JwtTokenValidator jwtTokenValidator;
    @Mock private UserUploadRepository userUploadRepository;

    private UploadAuthService service;

    @BeforeEach
    void setUp() {
        service = new UploadAuthService(jwtTokenValidator, userUploadRepository);
    }

    // ── bearer token extraction ───────────────────────────────────────────────

    @Nested
    @DisplayName("extractBearerToken (via requireJwt)")
    class ExtractBearerToken {

        @Test
        @DisplayName("throws UNAUTHORIZED when Authorization header is null")
        void nullHeader() {
            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws UNAUTHORIZED when Authorization header is blank")
        void blankHeader() {
            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "   "))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws UNAUTHORIZED when header does not start with 'Bearer '")
        void noBearerPrefix() {
            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Basic abc123"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws UNAUTHORIZED when token after 'Bearer ' is blank")
        void emptyTokenAfterBearer() {
            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer   "))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws UNAUTHORIZED when JwtTokenValidator throws JwtException")
        void jwtValidationFails() {
            when(jwtTokenValidator.decodeAndValidate("bad-token")).thenThrow(new JwtException("expired"));

            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer bad-token"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws INTERNAL_SERVER_ERROR when JwtTokenValidator throws IllegalStateException")
        void jwtValidatorIllegalState() {
            when(jwtTokenValidator.decodeAndValidate("bad-token"))
                    .thenThrow(new IllegalStateException("key init failed"));

            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer bad-token"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(INTERNAL_SERVER_ERROR));
        }
    }

    // ── role checking ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("role checking")
    class RoleChecking {

        @Test
        @DisplayName("throws FORBIDDEN when JWT has no STATE_ADMIN role")
        void missingRole() {
            Jwt jwt = Jwt.withTokenValue("token")
                    .header("alg", "RS256")
                    .claim("email", "user@x.com")
                    .claim("realm_access", Map.of("roles", List.of("PUMP_OPERATOR")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token")).thenReturn(jwt);

            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer token"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(FORBIDDEN));
        }

        @Test
        @DisplayName("accepts STATE_ADMIN role from realm_access.roles")
        void roleInRealmAccess() {
            Jwt jwt = Jwt.withTokenValue("token")
                    .header("alg", "RS256")
                    .claim("email", "admin@mp.gov")
                    .claim("realm_access", Map.of("roles", List.of("STATE_ADMIN")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token")).thenReturn(jwt);
            when(userUploadRepository.findUserIdByEmailOrPhone("tenant_mp", "admin@mp.gov", null))
                    .thenReturn(42);

            int userId = service.requireStateAdminUserId("tenant_mp", "Bearer token");
            assertThat(userId).isEqualTo(42);
        }

        @Test
        @DisplayName("accepts STATE_ADMIN role from resource_access client roles")
        void roleInResourceAccess() {
            Jwt jwt = Jwt.withTokenValue("token2")
                    .header("alg", "RS256")
                    .claim("email", "cadmin@mp.gov")
                    .claim("resource_access", Map.of(
                            "my-client", Map.of("roles", List.of("STATE_ADMIN"))
                    ))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token2")).thenReturn(jwt);
            when(userUploadRepository.findUserIdByEmailOrPhone("tenant_mp", "cadmin@mp.gov", null))
                    .thenReturn(7);

            int userId = service.requireStateAdminUserId("tenant_mp", "Bearer token2");
            assertThat(userId).isEqualTo(7);
        }

        @Test
        @DisplayName("throws FORBIDDEN when realm_access exists but roles list is empty")
        void emptyRolesList() {
            Jwt jwt = Jwt.withTokenValue("token3")
                    .header("alg", "RS256")
                    .claim("email", "empty@mp.gov")
                    .claim("realm_access", Map.of("roles", List.of()))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token3")).thenReturn(jwt);

            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer token3"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(FORBIDDEN));
        }
    }

    // ── user identity extraction ──────────────────────────────────────────────

    @Nested
    @DisplayName("user identity extraction")
    class UserIdentity {

        @Test
        @DisplayName("throws UNAUTHORIZED when email is null and preferred_username is null")
        void noIdentityClaims() {
            Jwt jwt = Jwt.withTokenValue("token4")
                    .header("alg", "RS256")
                    .claim("realm_access", Map.of("roles", List.of("STATE_ADMIN")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token4")).thenReturn(jwt);

            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer token4"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("throws UNAUTHORIZED when user not found in repository")
        void userNotFound() {
            Jwt jwt = Jwt.withTokenValue("token5")
                    .header("alg", "RS256")
                    .claim("email", "ghost@mp.gov")
                    .claim("realm_access", Map.of("roles", List.of("STATE_ADMIN")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token5")).thenReturn(jwt);
            when(userUploadRepository.findUserIdByEmailOrPhone("tenant_mp", "ghost@mp.gov", null))
                    .thenReturn(null);

            assertThatThrownBy(() -> service.requireStateAdminUserId("tenant_mp", "Bearer token5"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(UNAUTHORIZED));
        }

        @Test
        @DisplayName("falls back to preferred_username when email claim is absent")
        void usesPreferredUsername() {
            Jwt jwt = Jwt.withTokenValue("token6")
                    .header("alg", "RS256")
                    .claim("preferred_username", "adminuser")
                    .claim("realm_access", Map.of("roles", List.of("STATE_ADMIN")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            when(jwtTokenValidator.decodeAndValidate("token6")).thenReturn(jwt);
            when(userUploadRepository.findUserIdByEmailOrPhone("tenant_mp", null, "adminuser"))
                    .thenReturn(55);

            int userId = service.requireStateAdminUserId("tenant_mp", "Bearer token6");
            assertThat(userId).isEqualTo(55);
        }
    }
}
