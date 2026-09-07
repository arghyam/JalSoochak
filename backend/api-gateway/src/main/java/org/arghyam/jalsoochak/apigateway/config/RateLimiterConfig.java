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
            @Override
            public Mono<RateLimiter.Response> isAllowed(String routeId, String key) {
                if (!enabled) {
                    return Mono.just(new RateLimiter.Response(true, java.util.Map.of()));
                }
                return super.isAllowed(routeId, key);
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