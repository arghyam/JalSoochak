package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtAuthConverter")
class JwtAuthConverterTest {

    private static final String SUBJECT = "5b0f8f7e-2a4c-4d8e-9a51-0c6f3c1d2e7a";

    private final JwtAuthConverter converter = new JwtAuthConverter();

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static List<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("maps user_type to the USER_TYPE_ authority the other services grant")
    void mapsUserTypeToAnAuthority() {
        AbstractAuthenticationToken authentication =
                converter.convert(token(Map.of("user_type", "SECTION_OFFICER")));

        assertThat(authorities(authentication)).containsExactly("USER_TYPE_SECTION_OFFICER");
    }

    @Test
    @DisplayName("normalises the claim's case and padding")
    void normalisesTheClaim() {
        AbstractAuthenticationToken authentication =
                converter.convert(token(Map.of("user_type", " sub_divisional_officer ")));

        assertThat(authorities(authentication)).containsExactly("USER_TYPE_SUB_DIVISIONAL_OFFICER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("grants nothing for a blank user_type")
    void grantsNothingForABlankUserType(String userType) {
        assertThat(authorities(converter.convert(token(Map.of("user_type", userType))))).isEmpty();
    }

    @Test
    @DisplayName("grants nothing from realm roles: the staff routes admit by user type alone")
    void ignoresRealmRoles() {
        AbstractAuthenticationToken authentication = converter.convert(
                token(Map.of("realm_access", Map.of("roles", List.of("STATE_ADMIN", "SUPER_USER")))));

        assertThat(authorities(authentication)).isEmpty();
    }

    @Test
    @DisplayName("names the principal by the token subject")
    void namesThePrincipalBySubject() {
        assertThat(converter.convert(token(Map.of("user_type", "SECTION_OFFICER"))).getName())
                .isEqualTo(SUBJECT);
    }
}
