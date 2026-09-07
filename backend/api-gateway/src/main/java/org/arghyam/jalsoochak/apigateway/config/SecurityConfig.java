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
                        // Public auth endpoints
                        .pathMatchers(
                                "/user/api/v1/auth/login", "/api/v1/auth/login",
                                "/user/api/v1/auth/refresh", "/api/v1/auth/refresh",
                                "/user/api/v1/auth/logout", "/api/v1/auth/logout",
                                "/user/api/v1/auth/invites", "/api/v1/auth/invites",
                                "/user/api/v1/auth/invites/activate", "/api/v1/auth/invites/activate",
                                "/user/api/v1/auth/forgot-password", "/api/v1/auth/forgot-password",
                                "/user/api/v1/auth/reset-password", "/api/v1/auth/reset-password",
                                "/user/api/v1/auth/staff/otp", "/api/v1/auth/staff/otp",
                                "/user/api/v1/auth/staff/otp/verify", "/api/v1/auth/staff/otp/verify",
                                "/user/api/v1/public/**", "/api/v1/public/**",
                                // Staff and operator public endpoints
                                "/user/api/v1/state-admin/pump-operators/upload", "/api/v1/state-admin/pump-operators/upload",
                                "/user/api/v1/state-admin/user-scheme-mappings/upload", "/api/v1/state-admin/user-scheme-mappings/upload",
                                // Tenant service public endpoints
                                "/tenant/api/v1/tenants", "/api/v1/tenants",
                                // Message welcome trigger
                                "/message/api/v1/message/trigger-welcome-message", "/api/v1/message/trigger-welcome-message"
                        ).permitAll()
                        // Gateway and service health & docs
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
                                "/analytics/v3/api-docs/**",
                                "/anomaly/v3/api-docs/**"
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
