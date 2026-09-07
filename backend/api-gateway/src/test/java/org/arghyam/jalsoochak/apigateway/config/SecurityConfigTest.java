package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest
@ContextConfiguration(classes = {SecurityConfig.class})
public class SecurityConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void whenAccessingPublicHealth_thenNotUnauthorized() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isNotFound(); // 404 because controller isn't mapped, but NOT 401
    }

    @Test
    void whenAccessingSwaggerUi_thenNotUnauthorized() {
        webTestClient.get()
                .uri("/swagger-ui.html")
                .exchange()
                .expectStatus().isNotFound(); // Not 401
    }

    @Test
    void whenVerifyingStaffOtp_thenNotUnauthorized() {
        webTestClient.post()
                .uri("/api/v1/auth/staff/otp/verify")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post()
                .uri("/user/api/v1/auth/staff/otp/verify")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenAccessingFlatTenantStaffEndpoints_thenUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tenant/staff")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get()
                .uri("/api/v1/tenant/staff/counts/by-role")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void whenAccessingProtectedEndpointUnauthenticated_thenUnauthorized() {
        String[] protectedEndpoints = {
                "/api/v1/protected-resource",
                "/api/v1/pumpoperator/something",
                "/api/v1/telemetry/readings/device-1",
                "/api/v1/analytics/dashboard",
                "/api/v1/tenant-config/public/settings",
                "/actuator/prometheus"
        };

        for (String endpoint : protectedEndpoints) {
            webTestClient.get()
                    .uri(endpoint)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }
}
