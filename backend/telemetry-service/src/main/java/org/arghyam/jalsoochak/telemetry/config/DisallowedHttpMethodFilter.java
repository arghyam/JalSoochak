package org.arghyam.jalsoochak.telemetry.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Restricts the service to the HTTP methods it actually serves, and stops it advertising which those
 * are.
 *
 * <p><b>The finding.</b> A security audit reported {@code OPTIONS /api/v1/telemetry/schemes}
 * answering {@code 200 OK} with {@code Allow: POST,OPTIONS} (CWE-650) rather than rejecting the
 * request. Spring Boot defaults {@code spring.mvc.dispatch-options-request} to {@code true}, so
 * {@code RequestMappingHandlerMapping.handleNoMatch} hands an unmatched OPTIONS to the framework's
 * built-in {@code HttpOptionsHandler}, which enumerates the mapped methods into {@code Allow} and
 * adds an empty {@code Accept-Patch}. Nothing intercepted it because this service runs without Spring
 * Security — see {@link GlificWebhookAuthFilter} for why that is deliberate — and because neither
 * hand-rolled auth filter challenges a non-POST request on the Glific webhook paths.
 *
 * <p><b>An allowlist, not a denylist.</b> The audit asked for OPTIONS/TRACE/TRACK to be disabled;
 * permitting only {@link #ALLOWED_METHODS} answers that and also covers {@code CONNECT} and any
 * invented method without enumerating them. {@code PATCH} is in the set because
 * {@code PATCH /api/v1/telemetry/schemes/{id}/yesterday-final-reading} is a live ingestion route.
 *
 * <p><b>Why {@code spring.mvc.dispatch-options-request=false} was rejected.</b> It makes the
 * disclosure strictly worse. {@code FrameworkServlet.doOptions} only returns early when the dispatch
 * left an {@code Allow} header behind; otherwise it falls through to {@code HttpServlet.doOptions},
 * which reflects over the servlet hierarchy for overridden {@code doGet}/{@code doPost}/{@code doPut}/
 * {@code doDelete} — all four of which {@code FrameworkServlet} overrides — and answers {@code 200}
 * with {@code Allow: GET, HEAD, POST, PUT, DELETE, OPTIONS} plus {@code , PATCH} appended by
 * {@code FrameworkServlet}'s response wrapper. That is a superset of the two methods disclosed today.
 *
 * <p>That same fall-through is reachable on the current code without any property change: in Spring
 * 6.1 {@code CorsUtils.isCorsRequest} still compares scheme/host/port, so a <i>same-origin</i>
 * preflight is a pre-flight request that is not a CORS request. {@code DefaultCorsProcessor} then
 * returns without writing a header, {@code containsHeader("Allow")} is false, and the broad list is
 * served. Rejecting before the dispatcher closes that path too.
 *
 * <p><b>No CORS preflight carve-out.</b> This service publishes no {@code CorsConfigurationSource},
 * so a genuine cross-origin preflight can only ever be rejected, and no browser client calls
 * telemetry-service — Glific and the partner ingestion callers are both server-to-server. Rather
 * than carry an unused conditional, {@code DisallowedHttpMethodFilterOrderTest} asserts that no such
 * bean exists, so adding CORS here later fails the build and forces this decision to be revisited.
 *
 * <p><b>Why a filter and not a {@code HandlerInterceptor}.</b> Same reason as
 * {@link GlificWebhookAuthFilter}: {@code preHandle} runs after handler mapping, by which point
 * {@link TenantInterceptor} has applied the caller-supplied {@code X-Tenant-Code} to
 * {@link TenantContext}. A rejected request must not select a database schema.
 *
 * <p><b>Deliberate deviation from RFC 9110 §15.5.6</b>, which says a 405 response MUST generate an
 * {@code Allow} header. Emitting one would re-disclose exactly what this filter exists to hide, and
 * the filter runs before handler mapping so it does not know the resource's method set anyway.
 * {@link AllowHeaderSuppressingResponse} additionally strips {@code Allow} from the dispatcher's own
 * 405s, because {@code GET /api/v1/telemetry/schemes} otherwise still answers {@code Allow: POST} and
 * leaves the disclosure open to anyone who sends any wrong method. Nothing in this system performs
 * method discovery: Glific posts to 26 fixed URLs and partners post to fixed URLs.
 *
 * @see MethodGuardProperties for the {@code OFF} rollback switch
 */
@Component
@Order(DisallowedHttpMethodFilter.ORDER)
public class DisallowedHttpMethodFilter extends OncePerRequestFilter {

    /**
     * Runs after {@link RequestCorrelationFilter} (10) so rejections carry the request id in the MDC,
     * and ahead of {@link TelemetryApiKeyAuthFilter} (20) and {@link GlificWebhookAuthFilter} (30) so
     * a disallowed method never triggers an API-key lookup against the database and never reaches
     * {@link TenantInterceptor}. Every path answers an identical 405, so answering before
     * authentication reveals nothing that authenticating first would have concealed.
     *
     * <p>Consequence: {@code OPTIONS /api/v1/telemetry/readings} answers 405 rather than the 401 the
     * API-key filter used to give it. The method is unsupported regardless of credentials, so 405 is
     * the more accurate of the two.
     */
    public static final int ORDER = 15;

    /**
     * The methods this service maps. Everything else is rejected. Insertion-ordered so the startup
     * log reads predictably.
     */
    public static final Set<String> ALLOWED_METHODS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")));

    /**
     * Rejected methods that get their own metric tag. Anything else is counted as {@code OTHER} so a
     * caller sending randomised method names cannot grow the meter registry without bound.
     */
    private static final Set<String> TAGGED_METHODS = Set.of("OPTIONS", "TRACE", "TRACK", "CONNECT");

    private static final String METRIC_NAME = "http.method.rejected";

    /** Headers that enumerate a resource's methods. Both exist only to answer method discovery. */
    private static final String ALLOW_HEADER = "Allow";
    private static final String ACCEPT_PATCH_HEADER = "Accept-Patch";

    private static final Logger log = LoggerFactory.getLogger(DisallowedHttpMethodFilter.class);

    /**
     * Written directly rather than via {@code response.sendError}, which would render Tomcat's HTML
     * error page. Same shape as the other two filters in this package so a rejection is parseable by
     * whatever is calling. Names no methods.
     */
    private static final String METHOD_NOT_ALLOWED_BODY =
            "{\"success\":false,\"message\":\"Method Not Allowed\"}";

    private final MethodGuardProperties properties;
    private final MeterRegistry meterRegistry;

    public DisallowedHttpMethodFilter(MethodGuardProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (properties.getResolvedMode() == MethodGuardProperties.Mode.OFF) {
            // Full rollback: no rejection and no header suppression, so the previous behaviour is
            // restored exactly.
            filterChain.doFilter(request, response);
            return;
        }

        // HTTP methods are case-sensitive per RFC 9110 §9, so a lowercase "options" is a distinct
        // method that FrameworkServlet.service passes straight to processRequest rather than to
        // doOptions. Upper-casing here means the guard cannot be sidestepped by re-spelling it.
        String method = request.getMethod();
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);

        if (!ALLOWED_METHODS.contains(normalized)) {
            count(normalized);
            log.warn("http_method_rejected method={} path={} remoteAddr={}",
                    normalized, request.getRequestURI(), request.getRemoteAddr());
            writeMethodNotAllowed(response);
            return;
        }

        filterChain.doFilter(request, new AllowHeaderSuppressingResponse(response));
    }

    private void count(String normalizedMethod) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_NAME)
                .tag("method", TAGGED_METHODS.contains(normalizedMethod) ? normalizedMethod : "OTHER")
                .register(meterRegistry)
                .increment();
    }

    private static void writeMethodNotAllowed(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(METHOD_NOT_ALLOWED_BODY);
        response.getWriter().flush();
    }

    /**
     * Drops {@code Allow} and {@code Accept-Patch} on the way out.
     *
     * <p>Suppression is unconditional rather than conditional on a 405 status, because
     * {@code DefaultHandlerExceptionResolver.handleHttpRequestMethodNotSupported} sets {@code Allow}
     * <i>before</i> the status — a status-conditional wrapper would have to buffer every header until
     * {@code flushBuffer}. Unconditional is safe here because nothing on this service legitimately
     * emits either header: {@code Allow} comes only from {@code HttpOptionsHandler} (unreachable once
     * OPTIONS is rejected above) and from the 405 exception resolver, and {@code Accept-Patch} only
     * from {@code HttpOptionsHandler}.
     *
     * <p>Both {@code setHeader} and {@code addHeader} are intercepted: the framework uses the former,
     * but {@code HttpHeaders} copying can reach the latter, and letting one through would defeat the
     * other. {@code containsHeader} needs no override — the header never reaches the delegate, so it
     * already reports {@code false}.
     */
    private static final class AllowHeaderSuppressingResponse extends HttpServletResponseWrapper {

        private AllowHeaderSuppressingResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            if (isMethodEnumerating(name)) {
                return;
            }
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (isMethodEnumerating(name)) {
                return;
            }
            super.addHeader(name, value);
        }

        private static boolean isMethodEnumerating(String name) {
            return ALLOW_HEADER.equalsIgnoreCase(name) || ACCEPT_PATCH_HEADER.equalsIgnoreCase(name);
        }
    }
}
