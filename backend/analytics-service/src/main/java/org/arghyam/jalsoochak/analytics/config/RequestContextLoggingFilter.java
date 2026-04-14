package org.arghyam.jalsoochak.analytics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Ensures every request has minimal, consistent visibility in logs:
 * method + path (+ query) + status + duration, with a request id in MDC.
 */
@Component
@Slf4j
public class RequestContextLoggingFilter extends OncePerRequestFilter {

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startNanos = System.nanoTime();

        String requestId = firstNonBlank(request.getHeader(HEADER_REQUEST_ID), request.getHeader(HEADER_CORRELATION_ID));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        String tenantId = firstNonBlank(request.getParameter("tenant_id"), request.getHeader("tenant_id"));

        MDC.put("request_id", requestId);
        MDC.put("http_method", request.getMethod());
        MDC.put("http_path", request.getRequestURI());
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            MDC.put("http_query", request.getQueryString());
        }
        if (tenantId != null) {
            MDC.put("tenant_id", tenantId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = response.getStatus();

            // Log warnings for 4xx/5xx so the endpoint is always visible when there is an issue.
            if (status >= 400) {
                log.warn("{} -> {} ({} ms)", formatRequest(request), status, tookMs);
            } else {
                log.debug("{} -> {} ({} ms)", formatRequest(request), status, tookMs);
            }

            MDC.remove("request_id");
            MDC.remove("http_method");
            MDC.remove("http_path");
            MDC.remove("http_query");
            MDC.remove("tenant_id");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String formatRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query == null || query.isBlank()
                ? method + " " + uri
                : method + " " + uri + "?" + query;
    }
}

