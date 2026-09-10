package org.arghyam.jalsoochak.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * End-to-end cover for the gateway's rate limiting: route arguments from application.yml, the key
 * resolver and the Redis token bucket together. Upstream services are pointed at a dead port, so an
 * allowed request fails while proxying — anything other than 429 means the limiter let it through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rate-limit.enabled=true",
        "USER_SERVICE_URI=http://127.0.0.1:1"
})
@Testcontainers
class RateLimitingIntegrationTest {

    private static final int TOO_MANY_REQUESTS = 429;
    /** application.yml gives the OTP routes RATE_LIMIT_OTP_BURST_CAPACITY:5 at 1 token/second. */
    private static final int OTP_BURST_CAPACITY = 5;
    private static final String OTP_PATH = "/api/v1/auth/staff/otp";
    private static final String PREFIXED_OTP_PATH = "/user/api/v1/auth/staff/otp";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void allowsTheRoutesOwnBurstThenRejectsUntilTheBucketRefills() {
        String client = "203.0.113.10";

        for (int request = 1; request <= OTP_BURST_CAPACITY; request++) {
            assertNotEquals(TOO_MANY_REQUESTS, status(OTP_PATH, client), "request " + request + " of the burst");
        }
        assertEquals(TOO_MANY_REQUESTS, status(OTP_PATH, client),
                "the OTP route must reject at its own burst capacity, not the generous default one");

        // The bucket refills at 1 token/second; a lockout that never lifts is the regression this guards.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertNotEquals(TOO_MANY_REQUESTS, status(OTP_PATH, client)));
    }

    @Test
    void keepsABusyRouteFromExhaustingAnotherRoutesBudget() {
        String client = "203.0.113.20";
        exhaust(OTP_PATH, client);

        assertNotEquals(TOO_MANY_REQUESTS, status(LOGIN_PATH, client),
                "login has its own bucket and must survive an exhausted OTP bucket");
    }

    @Test
    void countsBothAliasesOfARouteAgainstOneBucket() {
        String client = "203.0.113.30";
        exhaust(OTP_PATH, client);

        assertEquals(TOO_MANY_REQUESTS, status(PREFIXED_OTP_PATH, client),
                "the same endpoint reached under /user/ must not hand out a second allowance");
    }

    private void exhaust(String path, String client) {
        for (int request = 0; request <= OTP_BURST_CAPACITY; request++) {
            status(path, client);
        }
        assertEquals(TOO_MANY_REQUESTS, status(path, client), "bucket for " + path + " should be empty");
    }

    private int status(String path, String client) {
        return webTestClient.post()
                .uri(path)
                .header("X-Forwarded-For", client)
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
    }
}
