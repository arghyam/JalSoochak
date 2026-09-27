package org.arghyam.jalsoochak.tenant.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecurityUtils}.
 *
 * Sets up a mock {@link SecurityContext} with a real {@link Jwt} object
 * to verify claim extraction behaviour without requiring a full Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityUtils Tests")
class SecurityUtilsTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Jwt buildJwt(Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                claims);
    }

    private static void setJwtInContext(Jwt jwt) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContext context = mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(context);
    }

    private static void setNonJwtPrincipalInContext() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("not-a-jwt");
        SecurityContext context = mock(SecurityContext.class);
        when(context.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(context);
    }

    // ── getCurrentUserUuid ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCurrentUserUuid")
    class GetCurrentUserUuid {

        @Test
        @DisplayName("returns JWT subject when authenticated")
        void returnsSubject_whenAuthenticated() {
            setJwtInContext(buildJwt(Map.of("sub", "uuid-abc-123")));

            assertThat(SecurityUtils.getCurrentUserUuid()).isEqualTo("uuid-abc-123");
        }

        @Test
        @DisplayName("throws when SecurityContext has no authentication")
        void throwsAuthCredentialsNotFoundException_whenNoAuthentication() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(SecurityUtils::getCurrentUserUuid)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                    .hasMessageContaining("outside an authenticated request context");
        }

        @Test
        @DisplayName("throws when principal is not a JWT (e.g. anonymous token)")
        void throwsAuthCredentialsNotFoundException_whenPrincipalIsNotJwt() {
            setNonJwtPrincipalInContext();

            assertThatThrownBy(SecurityUtils::getCurrentUserUuid)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }
    }

    // ── getCurrentUserTenantStateCode ────────────────────────────────────────────

    @Nested
    @DisplayName("getCurrentUserTenantStateCode")
    class GetCurrentUserTenantStateCode {

        @Test
        @DisplayName("returns value of tenant_state_code claim when present")
        void returnsTenantStateCode_whenClaimPresent() {
            setJwtInContext(buildJwt(Map.of("sub", "uid", "tenant_state_code", "MP")));

            assertThat(SecurityUtils.getCurrentUserTenantStateCode()).isEqualTo("MP");
        }

        @Test
        @DisplayName("returns null when tenant_state_code claim is absent (SUPER_USER token)")
        void returnsNull_whenClaimAbsent() {
            setJwtInContext(buildJwt(Map.of("sub", "uid")));

            assertThat(SecurityUtils.getCurrentUserTenantStateCode()).isNull();
        }

        @Test
        @DisplayName("throws when SecurityContext has no authentication")
        void throwsAuthCredentialsNotFoundException_whenNoAuthentication() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(SecurityUtils::getCurrentUserTenantStateCode)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }

        @Test
        @DisplayName("throws when principal is not a JWT (e.g. anonymous token)")
        void throwsAuthCredentialsNotFoundException_whenPrincipalIsNotJwt() {
            setNonJwtPrincipalInContext();

            assertThatThrownBy(SecurityUtils::getCurrentUserTenantStateCode)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }
    }
}
