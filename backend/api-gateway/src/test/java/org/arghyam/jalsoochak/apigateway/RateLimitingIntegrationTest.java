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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        int firstRejected = firstRejectedRequest(OTP_PATH, client);

        // The bucket refills in whole seconds (the limiter's Lua script reads Redis TIME), so a burst
        // that straddles a second boundary legitimately gains one token. Allow for that one refill
        // and no more: the default limit would not reject until request 41.
        assertTrue(firstRejected == OTP_BURST_CAPACITY + 1 || firstRejected == OTP_BURST_CAPACITY + 2,
                "the OTP route must reject at its own burst capacity, not the generous default one;"
                        + " first 429 came at request " + firstRejected);

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

        // A refill can land between the two calls and hand the shared bucket one token, so try the
        // alias twice: a shared bucket lets at most one through, while a separate bucket would still
        // hold its full burst and let both through.
        int rejected = 0;
        for (int request = 0; request < 2; request++) {
            if (status(PREFIXED_OTP_PATH, client) == TOO_MANY_REQUESTS) {
                rejected++;
            }
        }
        assertTrue(rejected >= 1, "the same endpoint reached under /user/ must not hand out a second allowance");
    }

    /**
     * Spends the route's bucket until it answers 429. Nothing is asserted about the bucket afterwards:
     * a whole-second refill can hand it a token at any moment.
     */
    private void exhaust(String path, String client) {
        assertTrue(firstRejectedRequest(path, client) > 0, "bucket for " + path + " should run out within its burst");
    }

    /**
     * @return the 1-based number of the first request answered 429, or -1 if none was within the burst
     *         plus two — room for one mid-burst refill and the request it lets through
     */
    private int firstRejectedRequest(String path, String client) {
        for (int request = 1; request <= OTP_BURST_CAPACITY + 2; request++) {
            if (status(path, client) == TOO_MANY_REQUESTS) {
                return request;
            }
        }
        return -1;
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
