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
    void whenAccessingProtectedEndpointUnauthenticated_thenUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/protected-resource")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
