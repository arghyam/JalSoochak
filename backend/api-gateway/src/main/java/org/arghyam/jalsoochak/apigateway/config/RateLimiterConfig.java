package org.arghyam.jalsoochak.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

@Configuration
public class RateLimiterConfig {

    /**
     * Suffix of the un-prefixed alias of a route that is also reachable under {@code /<service>/},
     * e.g. {@code otp-rate-limited-flat} alongside {@code otp-rate-limited}.
     */
    private static final String ALIAS_SUFFIX = "-flat";

    private static final String UNKNOWN_ROUTE = "unknown-route";
    private static final String UNKNOWN_CLIENT = "unknown-client";

    /**
     * Replaces the limiter Spring Cloud Gateway would auto-configure, adding only the
     * {@code rate-limit.enabled} kill switch and the fallback limits applied to routes that declare
     * none of their own. Per-route {@code redis-rate-limiter.*} arguments in application.yml are
     * bound by the framework and take precedence.
     */
    @Bean
    public RedisRateLimiter redisRateLimiter(
            @Value("${rate-limit.default.replenish-rate:20}") int replenishRate,
            @Value("${rate-limit.default.burst-capacity:40}") int burstCapacity,
            @Value("${rate-limit.enabled:false}") boolean enabled) {
        return new ToggleableRedisRateLimiter(replenishRate, burstCapacity, enabled);
    }

    /**
     * Buckets traffic by {@code <route group>:<caller>} — the authenticated user when there is one,
     * otherwise the client IP.
     *
     * <p>The route group has to be part of the key because {@link RedisRateLimiter} derives its Redis
     * keys from the resolved key alone. Without it every route would draw on one bucket per caller,
     * and the strict OTP and auth limits would be silently refilled at the rate of whichever busier
     * route shared the bucket.
     */
    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .filter(name -> !name.isBlank())
                .switchIfEmpty(Mono.fromSupplier(() -> clientIp(exchange)))
                .map(caller -> routeGroup(exchange) + ":" + caller);
    }

    /**
     * Collapses the two aliases of an endpoint — {@code /api/v1/auth/staff/otp} and
     * {@code /user/api/v1/auth/staff/otp} — onto one bucket, so alternating between them cannot
     * double a caller's allowance.
     */
    private String routeGroup(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null || route.getId() == null || route.getId().isBlank()) {
            return UNKNOWN_ROUTE;
        }
        String routeId = route.getId();
        return routeId.endsWith(ALIAS_SUFFIX)
                ? routeId.substring(0, routeId.length() - ALIAS_SUFFIX.length())
                : routeId;
    }

    /**
     * Takes the client IP from the <em>rightmost</em> entry of X-Forwarded-For.
     *
     * <p>nginx fronts the gateway with {@code proxy_set_header X-Forwarded-For
     * $proxy_add_x_forwarded_for}, which appends the peer address it actually observed to the right
     * of whatever the caller sent. The rightmost entry is therefore the one hop that cannot be
     * forged; the leftmost is caller-supplied and must never be used as a bucket key.
     *
     * <p>This holds only while nginx is the outermost proxy. Put another one in front (an ALB, a CDN)
     * and the rightmost entry becomes that proxy's address, collapsing every caller into a single
     * bucket — the resolver would then have to skip a known number of trusted hops instead.
     *
     * <p>A request arriving without passing through nginx would carry whatever header its sender
     * chose, rightmost entry included. No such path exists: the gateway's Service is ClusterIP with
     * no external address, so its port is reachable only from inside the cluster. Publishing it
     * through a NodePort or LoadBalancer would reopen that, and the resolver would then have to
     * check the peer address against the ingress network before believing the header at all.
     */
    private String clientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return UNKNOWN_CLIENT;
        }
        return remoteAddress.getAddress().getHostAddress();
    }
}
