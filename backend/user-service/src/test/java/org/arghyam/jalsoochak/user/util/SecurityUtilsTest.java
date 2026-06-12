package org.arghyam.jalsoochak.user.util;

import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.UnauthorizedAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SecurityUtils")
class SecurityUtilsTest {

    // --- helpers ---

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private static JwtAuthenticationToken jwtToken(String subject, String... authorities) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new JwtAuthenticationToken(jwt(subject), auths);
    }

    /**
     * Builds a minimal JWT string (header.payload.signature) where payload contains the given claims.
     * No real signing — only used for tests of extractSubFromTrustedKeycloakJwt / extractClaimFromTrustedKeycloakJwt.
     */
    private static String buildFakeJwtString(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        return header + "." + payload + ".fake-signature";
    }

    // --- getKeycloakId ---

    @Nested
    @DisplayName("getKeycloakId")
    class GetKeycloakId {

        @Test
        @DisplayName("returns subject from JwtAuthenticationToken")
        void returnsSubject() {
            JwtAuthenticationToken auth = jwtToken("kc-uuid-123");
            assertThat(SecurityUtils.getKeycloakId(auth)).isEqualTo("kc-uuid-123");
        }

        @Test
        @DisplayName("throws UnauthorizedAccessException for non-JWT authentication")
        void throwsForNonJwt() {
            Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass");
            assertThatThrownBy(() -> SecurityUtils.getKeycloakId(auth))
                    .isInstanceOf(UnauthorizedAccessException.class);
        }
    }

    // --- extractTenantCode ---

    @Nested
    @DisplayName("extractTenantCode")
    class ExtractTenantCode {

        @Test
        @DisplayName("returns state code from TENANT_ authority")
        void returnsTenantCode() {
            Authentication auth = jwtToken("sub", "TENANT_MP", "ROLE_STATE_ADMIN");
            assertThat(SecurityUtils.extractTenantCode(auth)).isEqualTo("MP");
        }

        @Test
        @DisplayName("returns null when no TENANT_ authority exists")
        void returnsNullWhenAbsent() {
            Authentication auth = jwtToken("sub", "ROLE_SUPER_USER");
            assertThat(SecurityUtils.extractTenantCode(auth)).isNull();
        }
    }

    // --- extractRole ---

    @Nested
    @DisplayName("extractRole")
    class ExtractRole {

        @Test
        @DisplayName("returns SUPER_STATE_ADMIN when present (highest priority)")
        void prefersSuperStateAdmin() {
            Authentication auth = jwtToken("sub",
                    "ROLE_SUPER_STATE_ADMIN", "ROLE_SUPER_USER", "ROLE_STATE_ADMIN");
            assertThat(SecurityUtils.extractRole(auth)).contains("SUPER_STATE_ADMIN");
        }

        @Test
        @DisplayName("returns SUPER_USER when SUPER_STATE_ADMIN absent")
        void prefersSuperUser() {
            Authentication auth = jwtToken("sub", "ROLE_SUPER_USER", "ROLE_STATE_ADMIN");
            assertThat(SecurityUtils.extractRole(auth)).contains("SUPER_USER");
        }

        @Test
        @DisplayName("returns STATE_ADMIN when only STATE_ADMIN present")
        void returnsStateAdmin() {
            Authentication auth = jwtToken("sub", "ROLE_STATE_ADMIN");
            assertThat(SecurityUtils.extractRole(auth)).contains("STATE_ADMIN");
        }

        @Test
        @DisplayName("returns empty when no recognized role is present")
        void returnsEmptyForUnknownRoles() {
            Authentication auth = jwtToken("sub", "ROLE_PUMP_OPERATOR");
            assertThat(SecurityUtils.extractRole(auth)).isEqualTo(Optional.empty());
        }
    }

    // --- extractSubFromTrustedKeycloakJwt ---

    @Nested
    @DisplayName("extractSubFromTrustedKeycloakJwt")
    class ExtractSubFromTrustedKeycloakJwt {

        @Test
        @DisplayName("extracts sub claim from a valid JWT payload")
        void extractsSub() {
            String token = buildFakeJwtString("{\"sub\":\"kc-uuid-abc\",\"email\":\"a@b.com\"}");
            assertThat(SecurityUtils.extractSubFromTrustedKeycloakJwt(token)).isEqualTo("kc-uuid-abc");
        }

        @Test
        @DisplayName("throws BadRequestException when sub claim is missing")
        void throwsWhenSubMissing() {
            String token = buildFakeJwtString("{\"email\":\"a@b.com\"}");
            assertThatThrownBy(() -> SecurityUtils.extractSubFromTrustedKeycloakJwt(token))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("throws BadRequestException for malformed token")
        void throwsForMalformedToken() {
            assertThatThrownBy(() -> SecurityUtils.extractSubFromTrustedKeycloakJwt("not.a.jwt"))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // --- extractClaimFromTrustedKeycloakJwt ---

    @Nested
    @DisplayName("extractClaimFromTrustedKeycloakJwt")
    class ExtractClaimFromTrustedKeycloakJwt {

        @Test
        @DisplayName("extracts an arbitrary string claim from payload")
        void extractsClaim() {
            String token = buildFakeJwtString("{\"email\":\"user@example.com\",\"sub\":\"id\"}");
            assertThat(SecurityUtils.extractClaimFromTrustedKeycloakJwt(token, "email"))
                    .isEqualTo("user@example.com");
        }

        @Test
        @DisplayName("returns null when the claim is absent")
        void returnsNullWhenAbsent() {
            String token = buildFakeJwtString("{\"sub\":\"id\"}");
            assertThat(SecurityUtils.extractClaimFromTrustedKeycloakJwt(token, "email")).isNull();
        }

        @Test
        @DisplayName("returns null for malformed token (no exception)")
        void returnsNullForMalformedToken() {
            assertThat(SecurityUtils.extractClaimFromTrustedKeycloakJwt("bad-token", "sub")).isNull();
        }
    }
}
