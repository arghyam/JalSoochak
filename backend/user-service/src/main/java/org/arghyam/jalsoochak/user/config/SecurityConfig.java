package org.arghyam.jalsoochak.user.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private final JwtAuthConverter jwtAuthConverter;
    private final Environment environment;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = allowedOrigins == null || allowedOrigins.isBlank()
                ? List.of()
                : Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin",
                "X-Requested-With", "X-Tenant-Code",
                "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    "/api/v1/auth/login",
                                    "/api/v1/auth/refresh",
                                    "/api/v1/auth/logout",
                                    "/api/v1/auth/invites",
                                    "/api/v1/auth/invites/activate",
                                    "/api/v1/auth/forgot-password",
                                    "/api/v1/auth/reset-password",
                                    "/api/v1/auth/staff/otp",
                                    "/api/v1/auth/staff/otp/verify",
                                    // Public (no-auth) endpoints
                                    "/api/v1/public/**",
                                    // Upload endpoint is authorized via UploadAuthService (JWT validation + role check),
                                    // not via Spring Security's JwtDecoder (which may require network access to Keycloak).
                                    "/api/v1/state-admin/pump-operators/upload",
                                    "/api/v1/state-admin/user-scheme-mappings/upload",
                                    "/error",
                                    "/actuator/health",
                                    "/actuator/info")
                            .permitAll();

                    // The anonymous village dashboard reaches exactly these three pump-operator
                    // endpoints. Everything else under /api/v1/pumpoperator/** — the person-scoped
                    // reads, the tenant-wide compliance list, {id}/readings, the scheme reads, and
                    // the numeric-id detail route — is only ever called by the Section Officer
                    // console, which already sends a bearer token, so it falls through to
                    // anyRequest().authenticated() below.
                    //
                    // The public detail route is keyed on the operator UUID, not the sequential id:
                    // /pump-operators/{id} stays authenticated so it cannot be walked 1..N.
                    auth.requestMatchers(HttpMethod.GET,
                                    "/api/v1/pumpoperator/pump-operators/by-uuid/*",
                                    "/api/v1/pumpoperator/pump-operators/by-scheme",
                                    "/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance")
                            .permitAll();
                    if (isProd) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated();
                    } else {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)));

        return http.build();
    }
}
