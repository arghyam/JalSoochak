package org.arghyam.jalsoochak.user.config;

import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private static AdminUserRow activeAdminRow(String uuid) {
        return new AdminUserRow(1L, uuid, "user@example.com", null, 1, 1, AdminUserStatus.ACTIVE, 1, null);
    }

    private static AdminUserRow inactiveAdminRow(String uuid) {
        return new AdminUserRow(2L, uuid, "inactive@example.com", null, 1, 1, AdminUserStatus.INACTIVE, 1, null);
    }

    private JwtAuthenticationToken authForUuid(String uuid) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("sub", uuid).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"),
                new SimpleGrantedAuthority("TENANT_MP")));
    }

    // ── Caller validation ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caller validation")
    class CallerValidation {

        @Test
        @DisplayName("is denied when caller is not found in the database")
        void deniedWhenCallerNotFound() {
            when(userCommonRepository.findAdminUserByUuid("missing-uuid")).thenReturn(Optional.empty());

            boolean result = evaluator.canAccessUser(10L, authForUuid("missing-uuid"));

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }

        @Test
        @DisplayName("is denied when caller exists but is not ACTIVE")
        void deniedWhenCallerInactive() {
            when(userCommonRepository.findAdminUserByUuid("inactive-uuid"))
                    .thenReturn(Optional.of(inactiveAdminRow("inactive-uuid")));

            boolean result = evaluator.canAccessUser(10L, authForUuid("inactive-uuid"));

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }
    }

    // ── SUPER_USER ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SUPER_USER")
    class SuperUser {

        @Test
        @DisplayName("can access any user without hitting the database")
        void canAccessAnyUser() {
            when(userCommonRepository.findAdminUserByUuid("su-uuid")).thenReturn(Optional.of(activeAdminRow("su-uuid")));

            boolean result = evaluator.canAccessUser(42L, superUserAuth());

            assertTrue(result);
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }
    }

    // ── STATE_ADMIN ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("STATE_ADMIN")
    class StateAdmin {

        @Test
        @DisplayName("can access user in their own tenant")
        void canAccessOwnTenant() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(true);

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertTrue(result);
        }

        @Test
        @DisplayName("cannot access user in a different tenant")
        void cannotAccessDifferentTenant() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(false);

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
        }

        @Test
        @DisplayName("returns false (not 404) when user does not exist — prevents ID probing")
        void returnsFalseForNonExistentUser() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userCommonRepository.userBelongsToTenant(999L, "MP")).thenReturn(false);

            boolean result = evaluator.canAccessUser(999L, stateAdminAuth("MP"));

            assertFalse(result);
        }

        @Test
        @DisplayName("is denied when JWT has no tenant_state_code claim")
        void deniedWhenNoTenantClaim() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));

            boolean result = evaluator.canAccessUser(10L, stateAdminAuthNoTenant());

            assertFalse(result);
            verify(userCommonRepository, never()).findTenantStatusByTenantId(anyInt());
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }

        @Test
        @DisplayName("tenant code normalization to uppercase")
        void tenantCodeCaseInsensitive() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(true);

            // Authority stored as TENANT_MP → extracted as "MP" via toUpperCase() in stateAdminAuth helper
            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("mp"));

            assertTrue(result);
        }

        @Test
        @DisplayName("is denied when tenant is ARCHIVED")
        void deniedWhenTenantArchived() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(6)); // ARCHIVED

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }

        @Test
        @DisplayName("can access user when tenant is SUSPENDED")
        void allowedWhenTenantSuspended() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(4)); // SUSPENDED
            when(userCommonRepository.userBelongsToTenant(10L, "MP")).thenReturn(true);

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertTrue(result);
        }

        @Test
        @DisplayName("is denied when tenant status is not found")
        void deniedWhenTenantStatusNotFound() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.empty());

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }
    }

    // ── No role ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Caller with no recognized role")
    class NoRole {

        @Test
        @DisplayName("is always denied")
        void alwaysDenied() {
            when(userCommonRepository.findAdminUserByUuid("anon-uuid")).thenReturn(Optional.of(activeAdminRow("anon-uuid")));

            boolean result = evaluator.canAccessUser(10L, noRoleAuth());

            assertFalse(result);
            verify(userCommonRepository, never()).userBelongsToTenant(anyLong(), anyString());
        }
    }

    // ── Exception safety ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Exception safety")
    class ExceptionSafety {

        @Test
        @DisplayName("returns false (not 500) when repository throws DataAccessException")
        void returnsFalseOnRepositoryException() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userCommonRepository.userBelongsToTenant(10L, "MP"))
                    .thenThrow(new QueryTimeoutException("DB timeout"));

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
        }

        @Test
        @DisplayName("returns false (not 500) when an unexpected RuntimeException is thrown")
        void returnsFalseOnUnexpectedException() {
            when(userCommonRepository.findAdminUserByUuid("sa-uuid")).thenReturn(Optional.of(activeAdminRow("sa-uuid")));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userCommonRepository.userBelongsToTenant(10L, "MP"))
                    .thenThrow(new RuntimeException("unexpected"));

            boolean result = evaluator.canAccessUser(10L, stateAdminAuth("MP"));

            assertFalse(result);
        }
    }
}
