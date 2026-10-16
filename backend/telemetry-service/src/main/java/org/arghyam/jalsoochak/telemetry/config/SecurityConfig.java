package org.arghyam.jalsoochak.telemetry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Keycloak JWT authentication for the routes the SO/SDO web UI calls with the officer's own token.
 *
 * <p><b>One chain, scoped to {@link #STAFF_BASE_PATH}.</b> Every other caller of this service has
 * its own credential and gate: the chatbot webhooks ({@link WebhookAuthFilter}), the partner
 * ingestion routes ({@link TelemetryApiKeyAuthFilter}) and the operations routes
 * ({@link InternalAuthFilter}). A request no chain matches passes through {@code FilterChainProxy}
 * untouched apart from its {@code StrictHttpFirewall}, so this chain adds nothing to those paths.
 * There is deliberately no catch-all chain.
 *
 * <p><b>Enforced here, not at the api-gateway.</b> Ingress also exposes this service directly, so a
 * gateway-only check could be bypassed.
 *
 * <p>The chain admits section and sub-divisional officers. Which tenant, user and scheme they may
 * write is decided by the service from the token's {@code tenant_state_code} and {@code sub} claims,
 * never from a header or body the caller controls.
 *
 * <p>CSRF is off because the token is a bearer header, not a cookie. CORS is off because the UI is
 * served from the same origin. It has to be turned off explicitly: Spring Security applies CORS on
 * its own whenever exactly one {@code CorsConfigurationSource} bean exists, and Spring MVC's
 * {@code HandlerMappingIntrospector} is one.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Base path of the routes authenticated by the officer's Keycloak token. */
    public static final String STAFF_BASE_PATH = "/api/v1/telemetry/staff";

    static final String SECTION_OFFICER_AUTHORITY = "USER_TYPE_SECTION_OFFICER";
    static final String SUB_DIVISIONAL_OFFICER_AUTHORITY = "USER_TYPE_SUB_DIVISIONAL_OFFICER";

    @Bean
    public SecurityFilterChain staffSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(STAFF_BASE_PATH + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest()
                        .hasAnyAuthority(SECTION_OFFICER_AUTHORITY, SUB_DIVISIONAL_OFFICER_AUTHORITY))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtAuthConverter())));
        return http.build();
    }
}
