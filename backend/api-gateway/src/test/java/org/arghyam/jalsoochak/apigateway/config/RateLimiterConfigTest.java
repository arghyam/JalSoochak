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
        var limiter = config.redisRateLimiter(1, 1, true);
        // First request on route A is allowed
        assertTrue(limiter.isAllowed("routeA", "key").block().isAllowed());
        // Second request on same route exceeds limit
        assertFalse(limiter.isAllowed("routeA", "key").block().isAllowed());
        // Same key on a different route should be allowed (separate bucket)
        assertTrue(limiter.isAllowed("routeB", "key").block().isAllowed());
    }

}
