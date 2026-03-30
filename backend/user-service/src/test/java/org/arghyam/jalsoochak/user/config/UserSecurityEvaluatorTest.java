package org.arghyam.jalsoochak.user.config;

import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSecurityEvaluator")
class UserSecurityEvaluatorTest {

    @Mock
    private UserCommonRepository userCommonRepository;

    private UserSecurityEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new UserSecurityEvaluator(userCommonRepository);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private JwtAuthenticationToken superUserAuth() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "su-uuid").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_SUPER_USER")));
    }

    private JwtAuthenticationToken stateAdminAuth(String tenantCode) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "sa-uuid").build();
        return new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("ROLE_STATE_ADMIN"),
                new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase())));
    }

    private JwtAuthenticationToken stateAdminAuthNoTenant() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "sa-uuid").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")));
    }

    private JwtAuthenticationToken noRoleAuth() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", "anon-uuid").build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    // ── SUPER_USER ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SUPER_USER")
    class SuperUser {

        @Test
        @DisplayName("can access any user without hitting the database")
        void canAccessAnyUser() {
            boolean result = evaluator.canAccessUser(42L, superUserAuth());

            assertTrue(result);
            verify(userCommonRepository, never()).userBelongsToTenant(42L, "any");
        }
    }

    // ── STATE_ADMIN ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("STATE_ADMIN")
    class StateAdmin {

        @Test
        @DisplayName("can access user in their own tenant")
        void canAccessOwnTenant() {
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(true);

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertTrue(result);
        }

        @Test
        @DisplayName("cannot access user in a different tenant")
        void cannotAccessDifferentTenant() {
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(false);

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
        }

        @Test
        @DisplayName("returns false (not 404) when user does not exist — prevents ID probing")
        void returnsFalseForNonExistentUser() {
            when(userCommonRepository.userBelongsToTenant(999L, "MP")).thenReturn(false);

            boolean result = evaluator.canAccessUser(999L, stateAdminAuth("MP"));

            assertFalse(result);
        }

        @Test
        @DisplayName("is denied when JWT has no tenant_state_code claim")
        void deniedWhenNoTenantClaim() {
            boolean result = evaluator.canAccessUser(10L, stateAdminAuthNoTenant());

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(10L, null);
        }

        @Test
        @DisplayName("tenant code comparison is case-insensitive")
        void tenantCodeCaseInsensitive() {
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(true);

            // Authority stored as TENANT_MP → extracted as "MP"; DB comparison is UPPER() in SQL
            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("mp"));

            assertTrue(result);
        }
    }

    // ── No role ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caller with no recognized role")
    class NoRole {

        @Test
        @DisplayName("is always denied")
        void alwaysDenied() {
            boolean result = evaluator.canAccessUser(10L, noRoleAuth());

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(10L, null);
        }
    }

    // ── Exception safety ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Exception safety")
    class ExceptionSafety {

        @Test
        @DisplayName("returns false (not 500) when repository throws DataAccessException")
        void returnsFalseOnRepositoryException() {
            when(userCommonRepository.userBelongsToTenant(10L, "MP"))
                    .thenThrow(new QueryTimeoutException("DB timeout"));

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
        }

        @Test
        @DisplayName("returns false (not 500) when an unexpected RuntimeException is thrown")
        void returnsFalseOnUnexpectedException() {
            when(userCommonRepository.userBelongsToTenant(10L, "MP"))
                    .thenThrow(new RuntimeException("unexpected"));

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
        }
    }
}
