package org.arghyam.jalsoochak.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Authorization rules for traffic entering through the gateway.
 *
 * <p>Every rule here mirrors the owning service's own SecurityConfig. That is not redundancy: a JWT
 * gate at the gateway on an endpoint the service publishes without one rejects callers that have no
 * token to send at all — Glific webhooks (X-Webhook-Token), vendor reading ingestion (X-Api-Key),
 * and the public dashboards and branding the login screen loads before anyone has signed in. When a
 * service's public list changes, this one has to change with it.
 *
 * <p>Each backend is reachable two ways — flat ({@code /api/v1/…}) and service-prefixed
 * ({@code /user/api/v1/…}, stripped by the route) — so every rule is registered for both forms.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** user-service: mirrors its permitAll list, minus paths no route reaches. */
    private static final String[] USER_SERVICE_PUBLIC = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/invites",
            "/api/v1/auth/invites/activate",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/staff/otp",
            "/api/v1/auth/staff/otp/verify",
            // Authorized by UploadAuthService, which validates the JWT itself rather than through
            // the JwtDecoder (that would need network access to Keycloak).
            "/api/v1/state-admin/pump-operators/upload",
            "/api/v1/state-admin/user-scheme-mappings/upload"
    };

    /**
     * user-service publishes exactly these three to the anonymous village dashboard, and only as GET.
     * Every other operator route — the sequential-id detail route included — and the staff directory
     * need a JWT.
     */
    private static final String[] USER_SERVICE_PUBLIC_READS = {
            "/api/v1/pumpoperator/pump-operators/by-uuid/*",
            "/api/v1/pumpoperator/pump-operators/by-scheme",
            "/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance"
    };

    /** tenant-service publishes these as GET only: branding and locations for the login screen. */
    private static final String[] TENANT_SERVICE_PUBLIC_READS = {
            "/api/v1/tenants",
            "/api/v1/tenants/*/config/public",
            "/api/v1/tenants/*/logo",
            "/api/v1/tenants/*/location-hierarchy/*",
            "/api/v1/tenants/*/locations/*"
    };

    /**
     * telemetry-service runs without Spring Security: every route under this prefix authenticates
     * with its own credential instead — X-Webhook-Token on the 26 Glific webhooks
     * (GlificWebhookRoutes), X-Api-Key on the vendor ingestion routes (TelemetryApiKeyAuthFilter).
     * Neither credential is a JWT, so a bearer-token gate here would reject all of it and protect
     * nothing that is not already protected upstream.
     */
    private static final String[] TELEMETRY_SERVICE_PUBLIC = {"/api/v1/telemetry/**"};

    /** scheme-service's public scheme lookups. */
    private static final String[] SCHEME_SERVICE_PUBLIC = {"/api/v1/public/**"};

    /** message-service's welcome trigger, called by the Glific flow. */
    private static final String[] MESSAGE_SERVICE_PUBLIC = {"/api/v1/message/trigger-welcome-message"};

    /**
     * analytics-service publishes its dashboards anonymously, GET only, and narrows that further to its
     * own list of public endpoints. The list lives in the service so it has a single home.
     */
    private static final String[] ANALYTICS_SERVICE_PUBLIC = {"/api/v1/analytics/**"};

    /**
     * The gateway's own health and the aggregated Swagger UI. anomaly-service is absent on purpose:
     * it ships no springdoc dependency, so it has no /v3/api-docs to aggregate.
     */
    private static final String[] GATEWAY_PUBLIC = {
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
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers(bothForms("/user", USER_SERVICE_PUBLIC)).permitAll()
                        .pathMatchers(HttpMethod.GET, bothForms("/user", USER_SERVICE_PUBLIC_READS)).permitAll()
                        .pathMatchers(bothForms("/telemetry", TELEMETRY_SERVICE_PUBLIC)).permitAll()
                        .pathMatchers(bothForms("/scheme", SCHEME_SERVICE_PUBLIC)).permitAll()
                        .pathMatchers(bothForms("/message", MESSAGE_SERVICE_PUBLIC)).permitAll()
                        .pathMatchers(HttpMethod.GET, bothForms("/tenant", TENANT_SERVICE_PUBLIC_READS)).permitAll()
                        .pathMatchers(HttpMethod.GET, bothForms("/analytics", ANALYTICS_SERVICE_PUBLIC)).permitAll()
                        .pathMatchers(GATEWAY_PUBLIC).permitAll()
                        // Anything a service does not publish itself needs a valid JWT.
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /** Expands each flat path into itself plus its {@code /<service>}-prefixed route alias. */
    private static String[] bothForms(String servicePrefix, String... paths) {
        return Stream.concat(Arrays.stream(paths), Arrays.stream(paths).map(path -> servicePrefix + path))
                .toArray(String[]::new);
    }
}
