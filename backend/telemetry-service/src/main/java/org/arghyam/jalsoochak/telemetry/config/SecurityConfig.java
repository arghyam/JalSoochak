package org.arghyam.jalsoochak.telemetry.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.authorizeHttpRequests(auth -> auth
                // Only the scheme admin endpoints are JWT-protected; webhook + API key flows remain public.
                .requestMatchers("/api/v1/telemetry/schemes/**").authenticated()
                .anyRequest().permitAll()
        );
        http.oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
        );
        return http.build();
    }

    /**
     * Provide a JwtDecoder bean even when no issuer/jwk URI is configured, so the app can start in
     * environments where JWT endpoints are not used. If the secured endpoints are hit without JWT
     * configuration, decoding will fail with a clear error.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri
    ) {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        // issuer-uri auto config is not used because we want explicit startup behavior.
        return token -> {
            throw new JwtException("JWT decoding is not configured (missing jwk-set-uri / issuer-uri)");
        };
    }
}

