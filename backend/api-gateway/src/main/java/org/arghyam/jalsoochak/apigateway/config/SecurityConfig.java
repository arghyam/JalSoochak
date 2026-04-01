package org.arghyam.jalsoochak.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        // Public auth endpoints (forwarded to user-service)
                        .pathMatchers(
                                "/user/api/v1/auth/login",
                                "/user/api/v1/auth/refresh",
                                "/user/api/v1/auth/logout",
                                "/user/api/v1/auth/invites",
                                "/user/api/v1/auth/invites/activate",
                                "/user/api/v1/auth/forgot-password",
                                "/user/api/v1/auth/reset-password",
                                "/user/api/v1/public/**",
                                // Public staff/operator endpoints (user-service SecurityConfig marks these permitAll)
                                "/user/api/v1/tenant/user/staff",
                                "/user/api/v1/tenant/user/staff/counts/by-role",
                                "/user/api/v1/tenant/staff",
                                "/user/api/v1/tenant/staff/counts/by-role",
                                "/user/api/v1/pumpoperator/**",
                                "/api/v1/pumpoperator/**",
                                // Upload endpoint — authorized via UploadAuthService internally
                                "/user/api/v1/state-admin/pump-operators/upload"
                        ).permitAll()
                        // Gateway and service health
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/webjars/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/user/v3/api-docs/**",
                                "/tenant/v3/api-docs/**",
                                "/telemetry/v3/api-docs/**",
                                "/message/v3/api-docs/**",
                                "/scheme/v3/api-docs/**",
                                "/analytics/v3/api-docs/**"
                        ).permitAll()
                        // All other routes require a valid JWT
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(org.springframework.security.config.Customizer.withDefaults())
                );

        return http.build();
    }
}
