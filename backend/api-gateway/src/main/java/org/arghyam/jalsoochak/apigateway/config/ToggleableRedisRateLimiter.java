package org.arghyam.jalsoochak.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The stock Redis token-bucket limiter with a kill switch in front of it.
 *
 * <p>Rate limiting is rolled out per environment via {@code rate-limit.enabled}. While it is off no
 * call is made to Redis at all, so an environment without a Redis instance still routes traffic;
 * while it is on every decision comes from {@link RedisRateLimiter}, which means one shared bucket
 * per key across gateway replicas, refills over time, and keys that expire on their own.
 */
class ToggleableRedisRateLimiter extends RedisRateLimiter {

    private static final Response ALLOWED = new Response(true, Map.of());

    private final boolean enabled;

    ToggleableRedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity, boolean enabled) {
        super(defaultReplenishRate, defaultBurstCapacity);
        this.enabled = enabled;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        if (!enabled) {
            return Mono.just(ALLOWED);
        }
        return super.isAllowed(routeId, id);
    }
}
