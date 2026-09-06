package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Pins the anonymous surface of the gateway.
 *
 * <p>All of {@code /api/v1/pumpoperator/**} was waved through here, which is how the sequential-id
 * operator route reached the internet. Three GET routes are anonymous now, and this runs the real
 * filter chain so a future {@code permitAll} that widens it fails in CI rather than in an audit.
 *
 * <p>No routes are registered in the test profile, so a request that clears security lands on a
 * 404 — that 404 is the assertion that it was permitted. A blocked request never reaches routing
 * and answers 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API Gateway SecurityConfig Tests")
class SecurityConfigTest {

    private static final String BY_SCHEME = "/api/v1/pumpoperator/pump-operators/by-scheme";
    private static final String BY_UUID =
            "/api/v1/pumpoperator/pump-operators/by-uuid/3f1a9c22-5b7e-4d38-9a10-8c4b2e6f0d71";

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @ParameterizedTest(name = "GET {0} is anonymous")
    @ValueSource(strings = {
            BY_UUID,
            BY_SCHEME,
            BY_SCHEME + "/reading-compliance",
            "/user" + BY_UUID,
            "/user" + BY_SCHEME,
            "/user" + BY_SCHEME + "/reading-compliance"
    })
    @DisplayName("the three village-dashboard routes are readable without a token")
    void publicRoutesAreAnonymous(String path) {
        webTestClient.get().uri(path).exchange().expectStatus().isEqualTo(NOT_FOUND);
    }

    @ParameterizedTest(name = "GET {0} requires a token")
    @ValueSource(strings = {
            // The enumeration vector the audit report walked from 1 to 21,315.
            "/api/v1/pumpoperator/pump-operators/1",
            "/api/v1/pumpoperator/pump-operators/reading-compliance",
            "/api/v1/pumpoperator/person/10/schemes",
            "/api/v1/pumpoperator/schemes/5/details",
            "/user/api/v1/pumpoperator/pump-operators/1",
            // Staff reads that sat in the same permitAll block as the operator tree.
            "/user/api/v1/tenant/user/staff",
            "/user/api/v1/tenant/staff"
    })
    @DisplayName("every other pump-operator and staff route is closed")
    void otherRoutesRequireAuthentication(String path) {
        webTestClient.get().uri(path).exchange().expectStatus().isEqualTo(UNAUTHORIZED);
    }

    /**
     * The permitAll on the public routes is GET-scoped. Without the method restriction the gateway
     * would forward an anonymous write and leave user-service as the only layer refusing it — the
     * two should agree on what is anonymous.
     */
    @ParameterizedTest(name = "{0} on a public path is not anonymous")
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    @DisplayName("non-GET methods on the public routes require a token")
    void nonGetMethodsOnPublicRoutesRequireAuthentication(String method) {
        webTestClient.method(HttpMethod.valueOf(method))
                .uri(BY_SCHEME)
                .exchange()
                .expectStatus().isEqualTo(UNAUTHORIZED);
    }

    @Test
    @DisplayName("health remains anonymous")
    void healthIsAnonymous() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("an unknown route requires a token")
    void unknownRouteRequiresAuthentication() {
        webTestClient.get().uri("/anything/else").exchange().expectStatus().isEqualTo(UNAUTHORIZED);
    }
}
