package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();
    private final KeyResolver keyResolver = config.userOrIpKeyResolver();

    @Test
    void resolvesAuthenticatedRequestsByPrincipal() {
        var exchange = routedExchange("user-service-flat", MockServerHttpRequest.get("/api/v1/users").build())
                .mutate()
                .principal(Mono.just((Principal) () -> "user-123"))
                .build();

        assertEquals("user-service:user-123", resolve(exchange));
    }

    @Test
    void resolvesPublicRequestsByRemoteIp() {
        var exchange = routedExchange("auth-rate-limited-flat", MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 12345))
                .build());

        assertEquals("auth-rate-limited:192.0.2.10", resolve(exchange));
    }

    @Test
    void resolvesForwardedPublicRequestsByRightmostForwardedIp() {
        var exchange = routedExchange("auth-rate-limited-flat", MockServerHttpRequest.post("/api/v1/auth/login")
                .header("X-Forwarded-For", "192.0.2.20, 10.0.0.5")
                .build());

        assertEquals("auth-rate-limited:10.0.0.5", resolve(exchange));
    }

    @Test
    void fallsBackToAPlaceholderWhenTheClientCannotBeIdentified() {
        var exchange = routedExchange("auth-rate-limited-flat", MockServerHttpRequest.post("/api/v1/auth/login").build());

        assertEquals("auth-rate-limited:unknown-client", resolve(exchange));
    }

    @Test
    void keysTheSameClientSeparatelyPerRoute() {
        var otp = routedExchange("otp-rate-limited-flat", forwardedRequest("/api/v1/auth/staff/otp"));
        var users = routedExchange("user-service-flat", forwardedRequest("/api/v1/users"));

        assertNotEquals(resolve(otp), resolve(users));
    }

    @Test
    void sharesOneKeyBetweenTheFlatAndPrefixedAliasOfARoute() {
        var flat = routedExchange("otp-rate-limited-flat", forwardedRequest("/api/v1/auth/staff/otp"));
        var prefixed = routedExchange("otp-rate-limited", forwardedRequest("/user/api/v1/auth/staff/otp"));

        assertEquals(resolve(flat), resolve(prefixed));
    }

    @Test
    void keysUnroutedRequestsWithoutFailing() {
        var exchange = MockServerWebExchange.from(forwardedRequest("/nowhere"));

        assertEquals("unknown-route:198.51.100.4", resolve(exchange));
    }

    @Test
    void disabledLimiterNeverRejectsAndNeverTouchesRedis() {
        var limiter = config.redisRateLimiter(1, 1, false);

        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.isAllowed("otp-rate-limited", "198.51.100.4").block().isAllowed(),
                    "request " + i + " must be allowed while rate limiting is switched off");
        }
    }

    /**
     * Guards against the limiter being replaced by an in-memory counter again: with rate limiting on
     * and no Redis wired up, the Redis-backed implementation must be the one refusing to answer.
     */
    @Test
    void enabledLimiterDelegatesToTheRedisBackedImplementation() {
        var limiter = config.redisRateLimiter(1, 1, true);

        assertThrows(IllegalStateException.class, () -> limiter.isAllowed("otp-rate-limited", "198.51.100.4"));
    }

    private String resolve(ServerWebExchange exchange) {
        return keyResolver.resolve(exchange).block();
    }

    private static MockServerHttpRequest forwardedRequest(String path) {
        return MockServerHttpRequest.get(path).header("X-Forwarded-For", "198.51.100.4").build();
    }

    private static MockServerWebExchange routedExchange(String routeId, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, Route.async()
                .id(routeId)
                .uri(URI.create("http://localhost:8082"))
                .predicate(candidate -> true)
                .build());
        return exchange;
    }
}
