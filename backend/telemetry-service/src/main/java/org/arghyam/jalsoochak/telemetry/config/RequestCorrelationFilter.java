package org.arghyam.jalsoochak.telemetry.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a request id in the MDC for the life of the request.
 *
 * <p>Ordered ahead of both authentication filters so a rejection from either is logged with its
 * {@code requestId}. Was unordered before, which placed it last among filters.
 */
@Component
@Order(RequestCorrelationFilter.ORDER)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    /**
     * Runs first so every downstream filter — {@link TelemetryApiKeyAuthFilter} and
     * {@link GlificWebhookAuthFilter} — logs with a request id.
     */
    public static final int ORDER = 10;

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_KEY);
        }
    }
}
