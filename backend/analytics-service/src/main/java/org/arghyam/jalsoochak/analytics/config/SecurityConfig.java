package org.arghyam.jalsoochak.analytics.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /**
     * The analytics endpoints the anonymous public dashboard actually calls.
     *
     * <p>Every one of these is reached from {@code features/dashboard/services/api/analytics-api.ts},
     * which uses the token-less {@code publicApiClient}. Everything else under
     * {@code /api/v1/analytics/**} — notably the {@code /user}-scoped reads, {@code operator-attendance},
     * {@code escalations} and {@code anomalies} — is only ever called by the Section Officer console
     * through the bearer-token {@code apiClient}, so it falls through to {@code authenticated()}.
     *
     * <p>This list is deliberately explicit rather than a wildcard: a new analytics endpoint is
     * private until someone adds it here, instead of being world-readable the moment it is written.
     */
    private static final String[] PUBLIC_ANALYTICS_ENDPOINTS = {
            "/api/v1/analytics/continuous-schemes",
            "/api/v1/analytics/critical-schemes",
            "/api/v1/analytics/national/dashboard",
            "/api/v1/analytics/national/dashboard/boundary",
            "/api/v1/analytics/outage-reasons",
            "/api/v1/analytics/outage-reasons/periodic",
            "/api/v1/analytics/reading-submission-rate",
            "/api/v1/analytics/scheme-regularity/average",
            "/api/v1/analytics/scheme-regularity/periodic",
            "/api/v1/analytics/scheme-regularity/periodic/national",
            "/api/v1/analytics/schemes/dashboard",
            "/api/v1/analytics/schemes/dashboard/download",
            "/api/v1/analytics/submission-status",
            "/api/v1/analytics/tenant_boundaries",
            "/api/v1/analytics/tenant_data",
            "/api/v1/analytics/water-quantity/periodic",
            "/api/v1/analytics/water-quantity/region-wise",
            "/api/v1/analytics/water-supply/average-per-region"
    };

    private final JwtAuthConverter jwtAuthConverter;
    private final Environment environment;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        boolean isProd = environment.acceptsProfiles(Profiles.of("prod"));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/info", "/error").permitAll();
                    // GET-only, and only the endpoints the public dashboard renders. The blanket
                    // PUT permitAll that used to sit here allowed unauthenticated state mutation
                    // and has been removed along with its only endpoint.
                    auth.requestMatchers(HttpMethod.GET, PUBLIC_ANALYTICS_ENDPOINTS).permitAll();
                    if (isProd) {
                        auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").authenticated();
                    } else {
                        auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                );

        return http.build();
    }
}
