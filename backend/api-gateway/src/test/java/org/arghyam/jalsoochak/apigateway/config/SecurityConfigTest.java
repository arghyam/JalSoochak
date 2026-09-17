package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The gateway's authorization rules must mirror each service's own SecurityConfig: a JWT gate here on
 * an endpoint the service publishes without one rejects callers that have no token to send — vendor
 * integrations keyed by X-Api-Key, Glific webhooks keyed by X-Webhook-Token, and the public
 * dashboards. No route is registered in this slice, so 404 means a request cleared security and 401
 * means it did not.
 */
@WebFluxTest
@ContextConfiguration(classes = {SecurityConfig.class})
class SecurityConfigTest {

    private static final int UNAUTHORIZED = 401;

    @Autowired
    private WebTestClient webTestClient;

    @ParameterizedTest
    @ValueSource(strings = {
            // user-service: the three operator reads the anonymous village dashboard makes
            "/api/v1/pumpoperator/pump-operators/by-uuid/3f1a9c22-5b7e-4d38-9a10-8c4b2e6f0d71",
            "/user/api/v1/pumpoperator/pump-operators/by-uuid/3f1a9c22-5b7e-4d38-9a10-8c4b2e6f0d71",
            "/api/v1/pumpoperator/pump-operators/by-scheme",
            "/user/api/v1/pumpoperator/pump-operators/by-scheme",
            "/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance",
            "/user/api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance",
            // tenant-service: branding and location lookups the login screen needs before a token exists
            "/api/v1/tenants",
            "/tenant/api/v1/tenants",
            "/api/v1/tenants/mp/config/public",
            "/tenant/api/v1/tenants/mp/config/public",
            "/api/v1/tenants/mp/logo",
            "/tenant/api/v1/tenants/mp/logo",
            "/api/v1/tenants/mp/location-hierarchy/district",
            "/tenant/api/v1/tenants/mp/location-hierarchy/district",
            "/api/v1/tenants/mp/locations/district",
            "/tenant/api/v1/tenants/mp/locations/district",
            // analytics-service: the public dashboards
            "/api/v1/analytics/water-supply/national",
            "/analytics/api/v1/analytics/water-supply/national",
            // scheme-service
            "/api/v1/public/schemes/7",
            "/scheme/api/v1/public/schemes/7"
    })
    void readsThatTheServicesPublishWithoutAJwtAreNotBlocked(String path) {
        assertPasses(HttpMethod.GET, path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // user-service auth flows
            "/api/v1/auth/login",
            "/user/api/v1/auth/login",
            "/api/v1/auth/staff/otp",
            "/user/api/v1/auth/staff/otp",
            "/api/v1/auth/staff/otp/verify",
            "/user/api/v1/auth/staff/otp/verify",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/invites/activate",
            // uploads authorized by UploadAuthService rather than the JwtDecoder
            "/api/v1/state-admin/pump-operators/upload",
            "/user/api/v1/state-admin/pump-operators/upload",
            "/api/v1/state-admin/user-scheme-mappings/upload",
            // telemetry-service: Glific webhooks (X-Webhook-Token) and vendor ingestion (X-Api-Key)
            "/api/v1/telemetry/readings/glific",
            "/telemetry/api/v1/telemetry/readings/glific",
            "/api/v1/telemetry/intro",
            "/api/v1/telemetry/take-meter-reading",
            "/api/v1/telemetry/readings",
            "/telemetry/api/v1/telemetry/readings",
            "/api/v1/telemetry/readings/formats/csv",
            // message-service
            "/api/v1/message/trigger-welcome-message",
            "/message/api/v1/message/trigger-welcome-message"
    })
    void writesAuthenticatedByTheirOwnCredentialAreNotBlocked(String path) {
        assertPasses(HttpMethod.POST, path);
    }

    @Test
    void vendorReadingCorrectionsKeyedByApiKeyAreNotBlocked() {
        assertPasses(HttpMethod.PATCH, "/api/v1/telemetry/schemes/7/yesterday-final-reading");
        assertPasses(HttpMethod.PUT, "/api/v1/telemetry/readings");
    }

    @Test
    void analyticsPublishesReadsOnly() {
        // #471 deleted the only anonymous analytics write, PUT /escalations/{id}/status.
        assertRequiresJwt(HttpMethod.PUT, "/api/v1/analytics/escalations/7/status");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // user-service: operator records by sequential id, officer-scoped reads and the staff
            // directory, all anonymous until #471 left only the three reads above public
            "/api/v1/pumpoperator/pump-operators/42",
            "/user/api/v1/pumpoperator/pump-operators/42",
            "/api/v1/pumpoperator/pump-operators/reading-compliance",
            "/api/v1/pumpoperator/schemes/7/reading-submissions",
            "/api/v1/tenant/staff",
            "/user/api/v1/tenant/staff",
            "/api/v1/tenant/staff/counts/by-role",
            "/api/v1/tenant/user/staff",
            "/user/api/v1/tenant/user/staff",
            "/api/v1/tenant/user/staff/counts/by-role",
            "/api/v1/users",
            "/user/api/v1/users",
            "/api/v1/state-admin/pump-operators",
            "/api/v1/scheme/7",
            "/scheme/api/v1/scheme/7",
            "/api/v1/anomalies",
            "/anomaly/api/v1/anomalies",
            "/api/v1/system/config",
            "/actuator/prometheus",
            "/actuator/gateway/routes"
    })
    void everythingElseStillRequiresAJwt(String path) {
        assertRequiresJwt(HttpMethod.GET, path);
    }

    @Test
    void publicReadsDoNotOpenTheMatchingWrites() {
        assertRequiresJwt(HttpMethod.POST, "/api/v1/tenants");
        assertRequiresJwt(HttpMethod.POST, "/api/v1/pumpoperator/pump-operators/by-scheme");
        assertRequiresJwt(HttpMethod.DELETE, "/api/v1/tenants/mp");
        assertRequiresJwt(HttpMethod.POST, "/api/v1/analytics/water-supply/national");
        assertRequiresJwt(HttpMethod.DELETE, "/api/v1/analytics/escalations/7");
    }

    @Test
    void anomalyServicePublishesNoApiDocsSoTheGatewayAdvertisesNone() {
        assertRequiresJwt(HttpMethod.GET, "/anomaly/v3/api-docs");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/info",
            "/swagger-ui.html",
            "/swagger-ui/index.css",
            "/v3/api-docs/swagger-config",
            "/user/v3/api-docs",
            "/tenant/v3/api-docs",
            "/telemetry/v3/api-docs",
            "/message/v3/api-docs",
            "/scheme/v3/api-docs",
            "/analytics/v3/api-docs"
    })
    void healthAndTheAggregatedDocsStayOpen(String path) {
        assertPasses(HttpMethod.GET, path);
    }

    private void assertPasses(HttpMethod method, String path) {
        assertNotEquals(UNAUTHORIZED, status(method, path),
                path + " is public upstream and must not be blocked by the gateway");
    }

    private void assertRequiresJwt(HttpMethod method, String path) {
        assertEquals(UNAUTHORIZED, status(method, path), path + " must require a JWT");
    }

    private int status(HttpMethod method, String path) {
        return webTestClient.method(method)
                .uri(path)
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
    }
}
