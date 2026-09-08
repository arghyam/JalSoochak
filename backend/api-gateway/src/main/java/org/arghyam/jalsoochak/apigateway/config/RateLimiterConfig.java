package org.arghyam.jalsoochak.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

@Configuration
public class RateLimiterConfig {
    @Bean
    public RedisRateLimiter redisRateLimiter(
            @Value("${rate-limit.default.replenish-rate:20}") int replenishRate,
            @Value("${rate-limit.default.burst-capacity:40}") int burstCapacity,
            @Value("${rate-limit.enabled:false}") boolean enabled) {
        return new RedisRateLimiter(replenishRate, burstCapacity) {
            private final java.util.concurrent.ConcurrentHashMap<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public reactor.core.publisher.Mono<org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response> isAllowed(String routeId, String key) {
                if (!enabled) {
                    return reactor.core.publisher.Mono.just(new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(true, java.util.Map.of()));
                }
                // Determine logical group for rate limiting to share buckets across legacy and flat routes.
                String group;
                if (routeId.contains("otp-rate-limited")) {
                    group = "otp";
                } else if (routeId.contains("auth-rate-limited")) {
                    group = "auth";
                } else {
                    group = routeId; // fallback to unique per route
                }
                String compositeKey = group + ":" + key;
                int count = counts.getOrDefault(compositeKey, 0);
                counts.put(compositeKey, count + 1);
                boolean allowed = count < replenishRate; // allow up to replenishRate requests per window
                return reactor.core.publisher.Mono.just(new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(allowed, java.util.Map.of()));
            }
        };
    }

    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> !name.isBlank())
                .switchIfEmpty(Mono.fromSupplier(() -> clientIp(
                        exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"),
                        exchange.getRequest().getRemoteAddress())));
    }

    private String clientIp(String forwardedFor, InetSocketAddress remoteAddress) {
        // Rightmost-IP choice:
        // When nginx does NOT use forwarded headers (use-forwarded-headers: false), the X-Forwarded-For header contains
        // a comma‑separated list of client IPs where the leftmost is the original client and the rightmost is the nearest
        // proxy. Selecting the rightmost IP ensures we rate‑limit based on the immediate hop (the load balancer or CDN).
        // If an ALB is placed in front and forwards headers, this logic would need to be adjusted.
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] ips = forwardedFor.split(",");
            return ips[ips.length - 1].trim();
        }
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown-client";
        }
        return remoteAddress.getAddress().getHostAddress();
    }
}