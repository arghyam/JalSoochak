package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void resolvesForwardedPublicRequestsByFirstForwardedIp() {
        var request = MockServerHttpRequest.get("/api/v1/auth/login")
                .header("X-Forwarded-For", "192.0.2.20, 10.0.0.5")
                .build();
        var exchange = MockServerWebExchange.from(request);

        assertEquals("192.0.2.20", config.userOrIpKeyResolver().resolve(exchange).block());
    }

    @Test
    void allowsRequestsWithoutContactingRedisWhenDisabled() {
        var limiter = config.redisRateLimiter(1, 1, false);

        assertEquals(true, limiter.isAllowed("test-route", "test-key").block().isAllowed());
    }
}
