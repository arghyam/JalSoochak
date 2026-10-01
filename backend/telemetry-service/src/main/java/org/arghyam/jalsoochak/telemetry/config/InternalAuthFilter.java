package org.arghyam.jalsoochak.telemetry.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Fail-closed {@code X-Internal-Token} gate for the operations routes under
 * {@code /api/v1/telemetry/internal}.
 *
 * <p>These routes act on a whole tenant at once, so they take an operations token held by the team
 * that runs them rather than a tenant's {@code X-Api-Key}, which the tenant's integrator also holds.
 * The api-gateway routes {@code /api/v1/telemetry/**} publicly, so this token is the only thing in
 * front of them.
 *
 * <p>Everything under the prefix needs the token, so a new internal route is protected the moment it
 * is mapped. A plain filter rather than Spring Security, for the reasons given on
 * {@link WebhookAuthFilter}.
 */
@Component
@Order(InternalAuthFilter.ORDER)
public class InternalAuthFilter extends OncePerRequestFilter {

    /**
     * Runs after {@link DisallowedHttpMethodFilter} (15), {@link TelemetryApiKeyAuthFilter} (20) and
     * {@link WebhookAuthFilter} (30), purely to keep the chain in a stable order: the three credential
     * gates guard disjoint paths.
     */
    public static final int ORDER = 40;

    public static final String TOKEN_HEADER = "X-Internal-Token";

    static final String INTERNAL_PREFIX = "/api/v1/telemetry/internal";

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    private static final String UNAUTHORIZED_BODY = "{\"success\":false,\"message\":\"Unauthorized\"}";

    private final InternalAuthProperties properties;

    public InternalAuthFilter(InternalAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = TelemetryApiKeyAuthFilter.normalizedPath(request);
        if (!isInternal(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(TOKEN_HEADER);
        if (properties.matches(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // The token is never logged, at any level.
        String reason = !properties.isEnabled() ? "disabled"
                : token == null || token.isBlank() ? "missing"
                : "invalid";
        log.warn("internal_auth_rejected reason={} method={} path={} remoteAddr={}",
                reason, request.getMethod(), path, request.getRemoteAddr());
        reject(response);
    }

    static boolean isInternal(String path) {
        return path.equals(INTERNAL_PREFIX) || path.startsWith(INTERNAL_PREFIX + "/");
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
        response.getWriter().flush();
    }
}
