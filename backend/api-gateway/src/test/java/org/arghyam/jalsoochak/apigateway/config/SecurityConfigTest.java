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
    void whenAccessingFlatTenantStaffEndpoints_thenNotUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tenant/staff")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get()
                .uri("/api/v1/tenant/staff/counts/by-role")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenAccessingProtectedEndpointUnauthenticated_thenUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/protected-resource")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
