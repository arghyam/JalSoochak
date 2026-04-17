package org.arghyam.jalsoochak.user.config;

import org.arghyam.jalsoochak.user.config.properties.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtAuthConverter")
class JwtAuthConverterTest {

    private static final String CLIENT_ID = "jalsoochak-client";

    private JwtAuthConverter converterFor(boolean singleTenantMode) {
        AppProperties props = new AppProperties();
        props.setDeploymentMode(singleTenantMode ? "SINGLE_TENANT" : "MULTI_TENANT");
        return new JwtAuthConverter(CLIENT_ID, props);
    }

    private static Jwt buildJwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Set<String> authorityNames(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("realm roles")
    class RealmRoles {

        @Test
        @DisplayName("extracts realm roles as ROLE_ prefixed authorities")
        void extractsRealmRoles() {
            Jwt jwt = buildJwt(Map.of(
                    "realm_access", Map.of("roles", List.of("STATE_ADMIN", "default-roles-realm")),
                    "preferred_username", "user@example.com"
            ));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token)).contains("ROLE_STATE_ADMIN", "ROLE_default-roles-realm");
        }

        @Test
        @DisplayName("handles missing realm_access claim gracefully")
        void handlesNoRealmAccess() {
            Jwt jwt = buildJwt(Map.of("preferred_username", "user@example.com"));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token))
                    .noneMatch(a -> a.startsWith("ROLE_"));
        }
    }

    @Nested
    @DisplayName("client roles")
    class ClientRoles {

        @Test
        @DisplayName("extracts client roles as ROLE_ prefixed authorities")
        void extractsClientRoles() {
            Jwt jwt = buildJwt(Map.of(
                    "resource_access", Map.of(
                            CLIENT_ID, Map.of("roles", List.of("STATE_ADMIN"))
                    ),
                    "preferred_username", "user@example.com"
            ));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token)).contains("ROLE_STATE_ADMIN");
        }

        @Test
        @DisplayName("ignores client roles when clientId is blank")
        void ignoresClientRolesWhenBlankClientId() {
            AppProperties props = new AppProperties();
            props.setDeploymentMode("MULTI_TENANT");
            JwtAuthConverter converter = new JwtAuthConverter("", props);

            Jwt jwt = buildJwt(Map.of(
                    "resource_access", Map.of(
                            CLIENT_ID, Map.of("roles", List.of("STATE_ADMIN"))
                    ),
                    "preferred_username", "user@example.com"
            ));

            AbstractAuthenticationToken token = converter.convert(jwt);

            // No client roles should be extracted when clientId is blank
            assertThat(authorityNames(token))
                    .noneMatch(a -> a.equals("ROLE_STATE_ADMIN"));
        }

        @Test
        @DisplayName("handles missing resource_access claim gracefully")
        void handlesNoResourceAccess() {
            Jwt jwt = buildJwt(Map.of("preferred_username", "user@example.com"));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token))
                    .noneMatch(a -> a.startsWith("ROLE_"));
        }
    }

    @Nested
    @DisplayName("tenant authority")
    class TenantAuthority {

        @Test
        @DisplayName("extracts TENANT_ authority from tenant_state_code claim")
        void extractsTenantAuthority() {
            Jwt jwt = buildJwt(Map.of(
                    "tenant_state_code", "mp",
                    "preferred_username", "user@example.com"
            ));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token)).contains("TENANT_MP");
        }

        @Test
        @DisplayName("produces no tenant authority when claim is absent")
        void noTenantAuthorityWhenAbsent() {
            Jwt jwt = buildJwt(Map.of("preferred_username", "user@example.com"));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token))
                    .noneMatch(a -> a.startsWith("TENANT_"));
        }
    }

    @Nested
    @DisplayName("user_type authority")
    class UserTypeAuthority {

        @Test
        @DisplayName("extracts USER_TYPE_ authority from user_type claim")
        void extractsUserTypeAuthority() {
            Jwt jwt = buildJwt(Map.of(
                    "user_type", "PUMP_OPERATOR",
                    "preferred_username", "user@example.com"
            ));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(authorityNames(token)).contains("USER_TYPE_PUMP_OPERATOR");
        }
    }

    @Nested
    @DisplayName("principal name")
    class PrincipalName {

        @Test
        @DisplayName("uses preferred_username as principal name when present")
        void usespreferredUsername() {
            Jwt jwt = buildJwt(Map.of("preferred_username", "admin@state.gov"));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(token.getName()).isEqualTo("admin@state.gov");
        }

        @Test
        @DisplayName("falls back to JWT subject when preferred_username is absent")
        void fallsBackToSubject() {
            Jwt jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .subject("kc-user-uuid")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);

            assertThat(token.getName()).isEqualTo("kc-user-uuid");
        }
    }

    @Nested
    @DisplayName("SUPER_STATE_ADMIN expansion (Single Tenant Mode)")
    class SuperStateAdminExpansion {

        @Test
        @DisplayName("expands SUPER_STATE_ADMIN to SUPER_USER and STATE_ADMIN in STM")
        void expandsInSingleTenantMode() {
            Jwt jwt = buildJwt(Map.of(
                    "realm_access", Map.of("roles", List.of("SUPER_STATE_ADMIN")),
                    "preferred_username", "superadmin@state.gov"
            ));

            AbstractAuthenticationToken token = converterFor(true).convert(jwt);
            Set<String> authorities = authorityNames(token);

            assertThat(authorities).contains(
                    "ROLE_SUPER_STATE_ADMIN",
                    "ROLE_SUPER_USER",
                    "ROLE_STATE_ADMIN"
            );
        }

        @Test
        @DisplayName("does NOT expand SUPER_STATE_ADMIN in Multi-Tenant Mode")
        void noExpansionInMultiTenantMode() {
            Jwt jwt = buildJwt(Map.of(
                    "realm_access", Map.of("roles", List.of("SUPER_STATE_ADMIN")),
                    "preferred_username", "superadmin@state.gov"
            ));

            AbstractAuthenticationToken token = converterFor(false).convert(jwt);
            Set<String> authorities = authorityNames(token);

            assertThat(authorities).contains("ROLE_SUPER_STATE_ADMIN");
            assertThat(authorities).doesNotContain("ROLE_SUPER_USER", "ROLE_STATE_ADMIN");
        }

        @Test
        @DisplayName("no effect when SUPER_STATE_ADMIN is absent")
        void noEffectWhenAbsent() {
            Jwt jwt = buildJwt(Map.of(
                    "realm_access", Map.of("roles", List.of("STATE_ADMIN")),
                    "preferred_username", "admin@state.gov"
            ));

            AbstractAuthenticationToken token = converterFor(true).convert(jwt);
            Set<String> authorities = authorityNames(token);

            assertThat(authorities).contains("ROLE_STATE_ADMIN");
            assertThat(authorities).doesNotContain("ROLE_SUPER_USER");
        }
    }
}
