package org.arghyam.jalsoochak.scheme.config;

import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeSecurityEvaluatorTest {

    @Mock
    SchemeDbRepository schemeDbRepository;

    @InjectMocks
    SchemeSecurityEvaluator evaluator;

    // --- canAccessTenant -----------------------------------------------------------------

    @Test
    void canAccessTenant_ownTenant_isAllowed() {
        Authentication auth = stateAdmin("AS");

        assertThat(evaluator.canAccessTenant("AS", auth)).isTrue();
    }

    @Test
    void canAccessTenant_otherTenant_isDenied() {
        Authentication auth = stateAdmin("AS");

        assertThat(evaluator.canAccessTenant("UP", auth)).isFalse();
        assertThat(evaluator.canAccessTenant("MH", auth)).isFalse();
        assertThat(evaluator.canAccessTenant("JK", auth)).isFalse();
        assertThat(evaluator.canAccessTenant("BR", auth)).isFalse();
    }

    @Test
    void canAccessTenant_ignoresCaseAndSurroundingWhitespace() {
        Authentication auth = stateAdmin(" as ");

        assertThat(evaluator.canAccessTenant("AS", auth)).isTrue();
        assertThat(evaluator.canAccessTenant(" As ", auth)).isTrue();
    }

    @Test
    void canAccessTenant_superUser_reachesAnyTenant() {
        Authentication auth = token(Map.of("sub", "national-admin"), "ROLE_SUPER_USER");

        assertThat(evaluator.canAccessTenant("AS", auth)).isTrue();
        assertThat(evaluator.canAccessTenant("UP", auth)).isTrue();
    }

    @Test
    void canAccessTenant_staffToken_reachesOnlyOwnTenant() {
        // Section Officer / operator tokens carry no admin role — the rule is role-agnostic.
        Authentication auth = token(claims("AS"), "ROLE_STAFF");

        assertThat(evaluator.canAccessTenant("AS", auth)).isTrue();
        assertThat(evaluator.canAccessTenant("UP", auth)).isFalse();
    }

    /**
     * In multi-tenant mode {@code JwtAuthConverter} does not expand SUPER_STATE_ADMIN to
     * SUPER_USER, so the role confers no cross-tenant reach here.
     */
    @Test
    void canAccessTenant_superStateAdminInMultiTenantMode_isScopedToOwnTenant() {
        Authentication auth = token(claims("AS"), "ROLE_SUPER_STATE_ADMIN");

        assertThat(evaluator.canAccessTenant("AS", auth)).isTrue();
        assertThat(evaluator.canAccessTenant("UP", auth)).isFalse();
    }

    /**
     * In Single Tenant Mode the converter adds ROLE_SUPER_USER alongside ROLE_SUPER_STATE_ADMIN,
     * which does grant the bypass.
     */
    @Test
    void canAccessTenant_superStateAdminInSingleTenantMode_isAllowedThrough() {
        Authentication auth = token(claims("AS"),
                "ROLE_SUPER_STATE_ADMIN", "ROLE_SUPER_USER", "ROLE_STATE_ADMIN");

        assertThat(evaluator.canAccessTenant("UP", auth)).isTrue();
    }

    @Test
    void canAccessTenant_missingTenantClaim_isDenied() {
        Authentication auth = token(Map.of("sub", "user-uuid"), "ROLE_STATE_ADMIN");

        assertThat(evaluator.canAccessTenant("AS", auth)).isFalse();
    }

    @Test
    void canAccessTenant_blankTenantClaim_isDenied() {
        Authentication auth = stateAdmin("   ");

        assertThat(evaluator.canAccessTenant("AS", auth)).isFalse();
    }

    @Test
    void canAccessTenant_blankOrMissingTenantCode_isDenied() {
        Authentication auth = stateAdmin("AS");

        assertThat(evaluator.canAccessTenant(null, auth)).isFalse();
        assertThat(evaluator.canAccessTenant("   ", auth)).isFalse();
    }

    @Test
    void canAccessTenant_nonJwtAuthentication_isDenied() {
        Authentication auth = new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        assertThat(evaluator.canAccessTenant("AS", auth)).isFalse();
    }

    @Test
    void canAccessTenant_nullAuthentication_isDenied() {
        assertThat(evaluator.canAccessTenant("AS", null)).isFalse();
    }

    // --- canAccessTenantId ---------------------------------------------------------------

    @Test
    void canAccessTenantId_ownTenant_isAllowed() {
        when(schemeDbRepository.findSchemaNameByTenantId(7)).thenReturn("tenant_as");

        assertThat(evaluator.canAccessTenantId(7, stateAdmin("AS"))).isTrue();
    }

    @Test
    void canAccessTenantId_otherTenant_isDenied() {
        when(schemeDbRepository.findSchemaNameByTenantId(9)).thenReturn("tenant_up");

        assertThat(evaluator.canAccessTenantId(9, stateAdmin("AS"))).isFalse();
    }

    @Test
    void canAccessTenantId_superUser_skipsTheLookup() {
        assertThat(evaluator.canAccessTenantId(9, token(Map.of("sub", "n"), "ROLE_SUPER_USER"))).isTrue();
    }

    /** Unknown ids are 403, not 404, so callers cannot probe for valid tenant ids. */
    @Test
    void canAccessTenantId_unknownTenant_isDenied() {
        when(schemeDbRepository.findSchemaNameByTenantId(404)).thenReturn(null);

        assertThat(evaluator.canAccessTenantId(404, stateAdmin("AS"))).isFalse();
    }

    @Test
    void canAccessTenantId_nullTenantId_isDenied() {
        assertThat(evaluator.canAccessTenantId(null, stateAdmin("AS"))).isFalse();
    }

    /** The evaluator runs inside a SpEL expression, so a repository failure must not propagate. */
    @Test
    void canAccessTenantId_repositoryFailure_isDeniedRatherThanThrown() {
        when(schemeDbRepository.findSchemaNameByTenantId(7))
                .thenThrow(new IllegalStateException("connection reset"));

        assertThat(evaluator.canAccessTenantId(7, stateAdmin("AS"))).isFalse();
    }

    // --- isCallerScopedToSchema ----------------------------------------------------------

    @Test
    void isCallerScopedToSchema_matchesResolvedSchemaName() {
        Authentication auth = stateAdmin("AS");

        assertThat(SchemeSecurityEvaluator.isCallerScopedToSchema(auth, "tenant_as")).isTrue();
        assertThat(SchemeSecurityEvaluator.isCallerScopedToSchema(auth, "tenant_up")).isFalse();
    }

    @Test
    void isCallerScopedToSchema_superUser_isAllowedThrough() {
        Authentication auth = token(Map.of("sub", "n"), "ROLE_SUPER_USER");

        assertThat(SchemeSecurityEvaluator.isCallerScopedToSchema(auth, "tenant_up")).isTrue();
    }

    @Test
    void isCallerScopedToSchema_missingClaim_isDenied() {
        Authentication auth = token(Map.of("sub", "user-uuid"), "ROLE_STATE_ADMIN");

        assertThat(SchemeSecurityEvaluator.isCallerScopedToSchema(auth, "tenant_as")).isFalse();
    }

    // --- helpers -------------------------------------------------------------------------

    private static Authentication stateAdmin(String tenantStateCode) {
        return token(claims(tenantStateCode), "ROLE_STATE_ADMIN");
    }

    private static Map<String, Object> claims(String tenantStateCode) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "user-uuid");
        claims.put("tenant_state_code", tenantStateCode);
        return claims;
    }

    private static Authentication token(Map<String, Object> claims, String... authorities) {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                claims);
        Set<GrantedAuthority> granted = new java.util.HashSet<>();
        for (String authority : authorities) {
            granted.add(new SimpleGrantedAuthority(authority));
        }
        return new JwtAuthenticationToken(jwt, granted, "user-uuid");
    }
}
