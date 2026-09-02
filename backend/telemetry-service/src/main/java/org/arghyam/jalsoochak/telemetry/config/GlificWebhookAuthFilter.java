package org.arghyam.jalsoochak.telemetry.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Authenticates the Glific webhook endpoints with a shared secret header.
 *
 * <p><b>Why a plain filter rather than Spring Security.</b> telemetry-service has no security starter
 * on the classpath. Adding {@code spring-boot-starter-security} would insert
 * {@code springSecurityFilterChain} in front of <i>every</i> request — the load-balancer health probe,
 * the anonymous Prometheus scrape, the {@code X-Api-Key} ingestion routes — so any path not explicitly
 * permitted would start returning 401 the moment the JAR shipped, all at once.
 * {@code oauth2-resource-server} would additionally make the service fetch Keycloak's discovery
 * document at startup, coupling meter-reading ingestion to Keycloak availability for the sake of a
 * caller that will never present a JWT. What is needed here is a comparison of one header against one
 * secret. {@link TelemetryApiKeyAuthFilter} is the sibling precedent in this package: same shape, a
 * different credential for a different family of callers.
 *
 * <p><b>Relationship to {@link TelemetryApiKeyAuthFilter}.</b> That filter guards the partner
 * ingestion routes under {@code /readings**} and {@code /schemes/*} with a per-tenant key, and exempts
 * {@code /readings/glific} and {@code /schemes} because those two are Glific webhooks. This filter is
 * what authenticates them. {@code GlificWebhookRouteCoverageTest} asserts that handoff in both
 * directions, so neither gate can be narrowed without the other noticing.
 *
 * <p><b>Why a filter rather than a {@code HandlerInterceptor}.</b> An interceptor runs after handler
 * mapping, by which point {@link TenantInterceptor} has already applied the caller-supplied
 * {@code X-Tenant-Code} to {@link TenantContext}. Running as a filter means a rejected request never
 * selects a database schema, never allocates a handler and never reads the body.
 *
 * @see GlificWebhookRoutes for why the match is a closed allowlist and not a path prefix
 */
@Component
@Order(GlificWebhookAuthFilter.ORDER)
public class GlificWebhookAuthFilter extends OncePerRequestFilter {

    /**
     * Runs after {@link RequestCorrelationFilter} (10) so rejections carry the request id, and after
     * {@link TelemetryApiKeyAuthFilter} (20) purely to keep the chain in a stable, documented order.
     * The two gates never contend: this filter's allowlist and that filter's protected prefixes are
     * disjoint, apart from {@code /readings/glific} and {@code /schemes}, which the API-key filter
     * exempts precisely so the webhook token can authenticate them here.
     */
    public static final int ORDER = 30;

    private static final Logger log = LoggerFactory.getLogger(GlificWebhookAuthFilter.class);

    private static final String METRIC_NAME = "telemetry.webhook.auth";
    private static final String RESULT_OK = "ok";
    private static final String RESULT_MISSING = "missing";
    private static final String RESULT_INVALID = "invalid";

    /**
     * Written directly rather than via {@code response.sendError}, which would render Tomcat's HTML
     * error page. A rejected request lands inside a live WhatsApp flow, so the body must stay JSON
     * that Glific can parse.
     */
    private static final String UNAUTHORIZED_BODY = "{\"success\":false,\"message\":\"Unauthorized\"}";

    private final WebhookAuthProperties properties;
    private final MeterRegistry meterRegistry;

    public GlificWebhookAuthFilter(WebhookAuthProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (properties.getResolvedMode() == WebhookAuthProperties.Mode.OFF) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = normalize(pathWithoutContext(request));
        if (!GlificWebhookRoutes.isProtected(request.getMethod(), path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = request.getHeader(properties.getHeaderName());
        String result;
        if (token == null || token.isBlank()) {
            result = RESULT_MISSING;
        } else if (properties.matches(token)) {
            result = RESULT_OK;
        } else {
            result = RESULT_INVALID;
        }
        count(result);

        if (RESULT_OK.equals(result)) {
            filterChain.doFilter(request, response);
            return;
        }

        // The token value is never logged, at any level. requestId comes from the MDC set by
        // RequestCorrelationFilter, which is ordered ahead of this filter.
        if (properties.getResolvedMode() == WebhookAuthProperties.Mode.AUDIT) {
            log.warn("Glific webhook auth {} — serving anyway (mode=AUDIT) path={}", result, path);
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Glific webhook auth {} — rejected (mode=ENFORCE) path={}", result, path);
        reject(response);
    }

    private void count(String result) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_NAME)
                .tag("result", result)
                .tag("mode", properties.getResolvedMode().name())
                .register(meterRegistry)
                .increment();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_BODY);
        response.getWriter().flush();
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Reduces a raw request URI to the form the handler mapping will see, so that a route cannot be
     * smuggled past the allowlist by spelling it differently.
     *
     * <p>Percent-decoding is applied because Tomcat decodes the URI before Spring matches it, so
     * {@code /%69ntro} reaches the {@code /intro} handler and must therefore be challenged here.
     * Empty segments (collapsing {@code //}), {@code .}, {@code ;}-path-parameters and a trailing
     * slash are all removed, and {@code ..} pops the preceding segment.
     *
     * <p>Note this only ever decides <i>whether to challenge</i>. A path that normalizes to something
     * outside the allowlist is passed through untouched, so ingestion routes are unaffected.
     */
    static String normalize(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }
        String decoded;
        try {
            decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed escape sequence. Match on the raw form; the container will usually have
            // rejected it already, and failing to decode must not mean failing to challenge.
            decoded = rawPath;
        }
        decoded = decoded.replace('\\', '/');

        Deque<String> resolved = new ArrayDeque<>();
        for (String rawSegment : decoded.split("/", -1)) {
            String segment = stripPathParameters(rawSegment);
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                resolved.pollLast();
                continue;
            }
            resolved.addLast(segment);
        }
        return "/" + String.join("/", resolved);
    }

    private static String stripPathParameters(String segment) {
        int idx = segment.indexOf(';');
        return idx >= 0 ? segment.substring(0, idx) : segment;
    }
}
