package org.arghyam.jalsoochak.telemetry.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed {@code X-Api-Key} gate for the server-to-server reading routes.
 *
 * <p>Authentication used to be a per-handler {@code resolveTenantIdFromRawApiKey(...).orElseThrow(401)}
 * block copied into each controller method. {@code POST /readings/reset-latest} shipped without that
 * block, so a destructive route sat unauthenticated on the public internet (CWE-287). Repeating a
 * check in every handler makes forgetting it the default failure mode, so the check lives here
 * instead: <b>every</b> path under the protected prefixes needs a valid key unless it is on the
 * explicit {@link #UNAUTHENTICATED_WEBHOOK_PATHS} allowlist. A new {@code /readings/**} endpoint is
 * therefore authenticated the moment it is mapped, and exposing one publicly takes a deliberate,
 * reviewable edit to that allowlist.
 *
 * <p>The resolved tenant is published as the {@link #TENANT_ID_ATTRIBUTE} request attribute so
 * handlers can authorize against the tenant the caller actually authenticated as, rather than the
 * unauthenticated {@code X-Tenant-Code} header or an unscoped cross-tenant lookup.
 *
 * <p>Handlers still resolve the key themselves as defence in depth: this filter is the gate, not the
 * only check, so a mapping that escapes the prefix rule is not silently public.
 */
@Component
@Order(TelemetryApiKeyAuthFilter.ORDER)
public class TelemetryApiKeyAuthFilter extends OncePerRequestFilter {

    /** Runs after {@link RequestCorrelationFilter} so rejections carry the request id in the MDC. */
    public static final int ORDER = 20;

    /** Request attribute carrying the tenant id resolved from a valid {@code X-Api-Key}. */
    public static final String TENANT_ID_ATTRIBUTE = "org.arghyam.jalsoochak.telemetry.authenticatedTenantId";

    public static final String API_KEY_HEADER = "X-Api-Key";

    private static final Logger log = LoggerFactory.getLogger(TelemetryApiKeyAuthFilter.class);

    private static final String TELEMETRY_BASE = "/api/v1/telemetry";
    private static final String READINGS_PREFIX = TELEMETRY_BASE + "/readings";
    private static final String SCHEMES_PREFIX = TELEMETRY_BASE + "/schemes";

    /**
     * Glific (WhatsApp) webhook routes that fall inside the protected prefixes. They are
     * unauthenticated at the application layer today — {@code telemetry-service} runs without Spring
     * Security and their only protection is network placement — and that is tracked as its own
     * finding. They are listed explicitly so the exemption is visible in review rather than implied
     * by a missing check.
     */
    private static final Set<String> UNAUTHENTICATED_WEBHOOK_PATHS = Set.of(
            READINGS_PREFIX + "/glific",
            SCHEMES_PREFIX
    );

    private static final String UNAUTHORIZED_BODY = """
            {"success":false,"message":"Invalid or missing API key",\
            "data":{"qualityStatus":"REJECTED","errorCode":"INVALID_API_KEY",\
            "message":"Invalid or missing API key"}}""";

    private final TelemetryApiKeyService telemetryApiKeyService;

    public TelemetryApiKeyAuthFilter(TelemetryApiKeyService telemetryApiKeyService) {
        this.telemetryApiKeyService = telemetryApiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = normalizedPath(request);
        if (!requiresApiKey(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        Optional<Integer> tenantId = telemetryApiKeyService.resolveTenantIdFromRawApiKey(apiKey);
        if (tenantId.isEmpty()) {
            // Logged at WARN, keyed on "api_key_rejected", so a burst of these (a caller sweeping
            // contactIds against a destructive route) is detectable without extra instrumentation.
            log.warn("api_key_rejected method={} path={} keyPresent={} remoteAddr={}",
                    request.getMethod(), path, apiKey != null && !apiKey.isBlank(), request.getRemoteAddr());
            writeUnauthorized(response);
            return;
        }

        request.setAttribute(TENANT_ID_ATTRIBUTE, tenantId.get());
        filterChain.doFilter(request, response);
    }

    /**
     * Deny by default inside the protected prefixes. {@code /readings} and everything beneath it is
     * server-to-server ingestion; under {@code /schemes} only the sub-paths are (bare
     * {@code POST /schemes} is a Glific selection webhook).
     */
    static boolean requiresApiKey(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (UNAUTHENTICATED_WEBHOOK_PATHS.contains(path)) {
            return false;
        }
        if (path.equals(READINGS_PREFIX) || path.startsWith(READINGS_PREFIX + "/")) {
            return true;
        }
        return path.startsWith(SCHEMES_PREFIX + "/");
    }

    /**
     * Matches the path the way the dispatcher will: decoded, semicolon content stripped, duplicate
     * slashes collapsed and dot segments resolved. Without this, {@code /telemetry/./readings/...} or
     * {@code /telemetry//readings/...} could route to a protected handler while slipping past a naive
     * prefix comparison here.
     */
    private static String normalizedPath(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (path == null || path.isBlank()) {
            return "";
        }
        path = StringUtils.cleanPath(path.replaceAll("/{2,}", "/"));
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(java.util.Locale.ROOT);
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
        response.getWriter().flush();
    }
}
