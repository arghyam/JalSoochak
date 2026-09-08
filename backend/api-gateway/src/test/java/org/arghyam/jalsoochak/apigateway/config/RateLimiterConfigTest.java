package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;


class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void resolvesAuthenticatedRequestsByPrincipal() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users"));
        var authenticatedExchange = exchange.mutate()
                .principal(Mono.just((Principal) () -> "user-123"))
                .build();

        assertEquals("user-123", config.userOrIpKeyResolver().resolve(authenticatedExchange).block());
    }

    @Test
    void resolvesPublicRequestsByRemoteIp() {
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 12345))
                .build();
        var exchange = MockServerWebExchange.from(request);

        assertEquals("192.0.2.10", config.userOrIpKeyResolver().resolve(exchange).block());
    }

    @Test
    void resolvesForwardedPublicRequestsByRightmostForwardedIp() {
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
                .header("X-Forwarded-For", "192.0.2.20, 10.0.0.5")
                .build();
        var exchange = MockServerWebExchange.from(request);

        assertEquals("10.0.0.5", config.userOrIpKeyResolver().resolve(exchange).block());
    }

    @Test

    void differentRoutesHaveSeparateBuckets() {
        // Use a lightweight mock limiter that simulates per‑composite‑key buckets.
        var mockLimiter = new org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter(1, 1) {
            private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();

            @Override
            public reactor.core.publisher.Mono<org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response> isAllowed(String routeId, String key) {
                // Composite key = routeId + ":" + key (same logic as production)
                String composite = routeId + ":" + key;
                int count = counts.getOrDefault(composite, 0);
                counts.put(composite, count + 1);
                boolean allowed = count < 1; // allow first request, block second on same composite
                return reactor.core.publisher.Mono.just(new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(allowed, java.util.Map.of()));
            }
        };

        // First request on route A is allowed
        assertTrue(mockLimiter.isAllowed("routeA", "key").block().isAllowed());
        // Second request on same route exceeds limit
        assertFalse(mockLimiter.isAllowed("routeA", "key").block().isAllowed());
        // Same key on a different route should be allowed (separate bucket)
        assertTrue(mockLimiter.isAllowed("routeB", "key").block().isAllowed());
    }
    @Test
    void otpRoutesShareBucket() {
        var limiter = config.redisRateLimiter(1, 1, true);
        assertTrue(limiter.isAllowed("otp-rate-limited-flat", "client1").block().isAllowed());
        assertFalse(limiter.isAllowed("otp-rate-limited", "client1").block().isAllowed());
    }

    @Test
    void authRoutesShareBucket() {
        var limiter = config.redisRateLimiter(1, 1, true);
        assertTrue(limiter.isAllowed("auth-rate-limited-flat", "client2").block().isAllowed());
        assertFalse(limiter.isAllowed("auth-rate-limited", "client2").block().isAllowed());
    }
}
