package org.arghyam.jalsoochak.scheme.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthConverterTest {

    @Test
    void convert_extractsAuthoritiesAndPreferredUsername() {
        JwtAuthConverter converter = new JwtAuthConverter("scheme-client");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-user")
                .claim("preferred_username", "preferred-user")
                .claim("tenant_state_code", "ka")
                .claim("user_type", "state_admin")
                .claim("realm_access", Map.of("roles", List.of("offline_access", "state_admin")))
                .claim("resource_access", Map.of("scheme-client", Map.of("roles", List.of("editor"))))
                .build();

        JwtAuthenticationToken token = (JwtAuthenticationToken) converter.convert(jwt);
        Set<String> authorities = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        assertThat(token.getName()).isEqualTo("preferred-user");
        assertThat(authorities).contains(
                "ROLE_offline_access",
                "ROLE_state_admin",
                "ROLE_editor",
                "TENANT_KA",
                "USER_TYPE_STATE_ADMIN"
        );
    }

    @Test
    void convert_usesSubjectWhenPreferredUsernameMissing() {
        JwtAuthConverter converter = new JwtAuthConverter("");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("fallback-subject")
                .build();

        JwtAuthenticationToken token = (JwtAuthenticationToken) converter.convert(jwt);

        assertThat(token.getName()).isEqualTo("fallback-subject");
        assertThat(token.getAuthorities()).isEmpty();
    }
}
