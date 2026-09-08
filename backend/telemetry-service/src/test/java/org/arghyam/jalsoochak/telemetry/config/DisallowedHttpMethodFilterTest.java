package org.arghyam.jalsoochak.telemetry.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter closes the "OPTIONS method enabled" audit finding (CWE-650). These tests pin the two
 * halves of that: a method the service does not map is rejected with 405 before it reaches anything
 * else, and no response ever carries a header that enumerates the supported methods.
 *
 * <p>The other half of the contract is negative — the methods the service <i>does</i> serve must be
 * untouched — so the Glific webhook routes and the partner ingestion routes are asserted explicitly
 * rather than left to integration testing.
 */
@DisplayName("DisallowedHttpMethodFilter")
class DisallowedHttpMethodFilterTest {

    private static final String AUDITED_PATH = "/api/v1/telemetry/schemes";

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Nested
    @DisplayName("rejecting a method the service does not map")
    class Rejecting {

        @Test
        @DisplayName("OPTIONS on the audited path is 405 and never reaches the dispatcher")
        void optionsOnTheAuditedPathIsRejectedWith405AndNeverReachesTheDispatcher() throws Exception {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("OPTIONS", AUDITED_PATH), response, chain);

            assertThat(response.getStatus()).isEqualTo(405);
            assertThat(chain.getRequest())
                    .as("a disallowed method must not reach the dispatcher, TenantInterceptor or either auth gate")
                    .isNull();
        }

        @Test
        @DisplayName("the rejection carries neither Allow nor Accept-Patch")
        void theRejectionCarriesNoAllowAndNoAcceptPatchHeader() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("OPTIONS", AUDITED_PATH), response, new MockFilterChain());

            assertThat(response.getHeader("Allow")).isNull();
            assertThat(response.getHeader("Accept-Patch")).isNull();
        }

        @Test
        @DisplayName("the body is JSON and names no methods")
        void theRejectionBodyIsJsonAndNamesNoMethods() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("OPTIONS", AUDITED_PATH), response, new MockFilterChain());

            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString())
                    .hasSizeLessThan(256)
                    .doesNotContain("POST", "GET", "PATCH", "PUT", "DELETE");
        }

        @Test
        @DisplayName("every Glific webhook path rejects OPTIONS")
        void everyGlificWebhookPathRejectsOptions() throws Exception {
            for (String path : GlificWebhookRoutes.absolutePaths()) {
                MockFilterChain chain = new MockFilterChain();
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter().doFilter(request("OPTIONS", path), response, chain);

                assertThat(response.getStatus()).as("%s must reject OPTIONS", path).isEqualTo(405);
                assertThat(chain.getRequest()).as("%s must not dispatch OPTIONS", path).isNull();
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"TRACE", "TRACK", "CONNECT"})
        @DisplayName("TRACE, TRACK and CONNECT are rejected regardless of the connector's allowTrace")
        void traceTrackAndConnectAreRejectedWith405(String method) throws Exception {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request(method, AUDITED_PATH), response, chain);

            assertThat(response.getStatus()).isEqualTo(405);
            assertThat(chain.getRequest()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"options", "Options", "oPtIoNs", "trace"})
        @DisplayName("a re-spelled method name cannot sidestep the guard")
        void lowercaseAndMixedCaseMethodNamesAreRejected(String method) throws Exception {
            // HTTP methods are case-sensitive per RFC 9110 §9, so "options" is a distinct method that
            // FrameworkServlet.service hands straight to processRequest rather than to doOptions.
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request(method, AUDITED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(405);
        }

        @Test
        @DisplayName("an invented method is rejected, proving this is an allowlist")
        void anUnknownMethodIsRejectedWith405() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("FROBNICATE", AUDITED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(405);
        }

        @Test
        @DisplayName("a cross-origin preflight is 405 rather than the framework's 403")
        void crossOriginPreflightIsRejected() throws Exception {
            MockHttpServletRequest request = request("OPTIONS", AUDITED_PATH);
            request.addHeader("Origin", "https://dashboard.example");
            request.addHeader("Access-Control-Request-Method", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(405);
        }

        @Test
        @DisplayName("a same-origin preflight is 405, closing the broad-Allow fall-through")
        void sameOriginPreflightIsRejectedRatherThanFallingThroughToTheServletsBroadAllowList()
                throws Exception {
            // In Spring 6.1 CorsUtils.isCorsRequest still compares scheme/host/port, so this is a
            // pre-flight request that is NOT a CORS request. DefaultCorsProcessor therefore returns
            // without writing Allow, and FrameworkServlet.doOptions falls through to
            // HttpServlet.doOptions and its much broader method list. See the dispatcher test for the
            // proof; this pins that the filter answers first.
            MockHttpServletRequest request = request("OPTIONS", AUDITED_PATH);
            request.addHeader("Origin", "http://localhost");
            request.addHeader("Access-Control-Request-Method", "POST");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(405);
            assertThat(response.getHeader("Allow")).isNull();
        }

        @Test
        @DisplayName("rejections are counted, and an invented method cannot grow the registry")
        void rejectionsAreCountedUnderABoundedSetOfTags() throws Exception {
            filter().doFilter(request("OPTIONS", AUDITED_PATH), new MockHttpServletResponse(),
                    new MockFilterChain());
            filter().doFilter(request("FROBNICATE", AUDITED_PATH), new MockHttpServletResponse(),
                    new MockFilterChain());
            filter().doFilter(request("WIBBLE", AUDITED_PATH), new MockHttpServletResponse(),
                    new MockFilterChain());

            assertThat(meterRegistry.get("http.method.rejected").tag("method", "OPTIONS").counter().count())
                    .isEqualTo(1.0d);
            assertThat(meterRegistry.get("http.method.rejected").tag("method", "OTHER").counter().count())
                    .as("unrecognised methods share one tag so tag cardinality stays bounded")
                    .isEqualTo(2.0d);
        }

        @Test
        @DisplayName("a null meter registry does not break the rejection")
        void rejectionWorksWithoutAMeterRegistry() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            new DisallowedHttpMethodFilter(properties("ENFORCE"), null)
                    .doFilter(request("OPTIONS", AUDITED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(405);
        }
    }

    @Nested
    @DisplayName("leaving the methods the service serves alone")
    class Passthrough {

        @Test
        @DisplayName("POST to every Glific webhook path is untouched")
        void postToEveryGlificWebhookPathIsUntouched() throws Exception {
            for (String path : GlificWebhookRoutes.absolutePaths()) {
                MockFilterChain chain = new MockFilterChain();
                MockHttpServletRequest request = request("POST", path);

                filter().doFilter(request, new MockHttpServletResponse(), chain);

                assertThat(chain.getRequest()).as("%s must still dispatch POST", path).isNotNull();
            }
        }

        @Test
        @DisplayName("the partner X-Api-Key ingestion routes are untouched")
        void apiKeyIngestionRoutesAreUntouched() throws Exception {
            String[][] routes = {
                    {"POST", "/api/v1/telemetry/readings"},
                    {"PUT", "/api/v1/telemetry/readings"},
                    {"POST", "/api/v1/telemetry/readings/formats/assam"},
                    {"POST", "/api/v1/telemetry/readings/reset-latest"},
                    {"PATCH", "/api/v1/telemetry/schemes/42/yesterday-final-reading"},
            };

            for (String[] route : routes) {
                MockFilterChain chain = new MockFilterChain();
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter().doFilter(request(route[0], route[1]), response, chain);

                assertThat(chain.getRequest())
                        .as("%s %s must reach the API-key gate", route[0], route[1])
                        .isNotNull();
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }

        @Test
        @DisplayName("the Kubernetes health probe is untouched")
        void healthProbeIsUntouched() throws Exception {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("GET", "/actuator/health"), response, chain);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @ParameterizedTest
        @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("every allowed method is dispatched")
        void everyAllowedMethodIsDispatched(String method) throws Exception {
            MockFilterChain chain = new MockFilterChain();

            filter().doFilter(request(method, AUDITED_PATH), new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Allow-header suppression on dispatched requests")
    class AllowSuppression {

        @ParameterizedTest
        @ValueSource(strings = {"Allow", "allow", "ALLOW", "Accept-Patch", "accept-patch"})
        @DisplayName("a method-enumerating header set downstream is dropped, whatever its casing")
        void methodEnumeratingHeadersSetDownstreamAreDropped(String headerName) throws Exception {
            // GET on a POST-only mapping: DefaultHandlerExceptionResolver sets Allow before the
            // status, which is why the wrapper strips unconditionally rather than on a 405.
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("GET", AUDITED_PATH), response, setsHeader(headerName, "POST"));

            assertThat(response.getHeader(headerName)).isNull();
            assertThat(response.getHeaderNames()).doesNotContain(headerName);
        }

        @Test
        @DisplayName("addHeader is intercepted as well as setHeader")
        void addHeaderIsInterceptedToo() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("GET", AUDITED_PATH), response,
                    (req, res) -> ((HttpServletResponse) res).addHeader("Allow", "POST"));

            assertThat(response.getHeader("Allow")).isNull();
        }

        @Test
        @DisplayName("unrelated headers still pass through")
        void unrelatedHeadersArePreserved() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request("GET", AUDITED_PATH), response, setsHeader("X-Request-Id", "abc123"));

            assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc123");
        }
    }

    @Nested
    @DisplayName("OFF mode")
    class OffMode {

        @Test
        @DisplayName("dispatches a method that ENFORCE would reject")
        void offModeDispatchesADisallowedMethod() throws Exception {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            offFilter().doFilter(request("OPTIONS", AUDITED_PATH), response, chain);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("does not wrap the response, so Allow survives")
        void offModeDoesNotSuppressTheAllowHeader() throws Exception {
            // The rollback switch has to restore the previous behaviour exactly. If OFF still
            // stripped Allow it would be a partial rollback, which is worse than none.
            MockHttpServletResponse response = new MockHttpServletResponse();

            offFilter().doFilter(request("GET", AUDITED_PATH), response, setsHeader("Allow", "POST"));

            assertThat(response.getHeader("Allow")).isEqualTo("POST");
        }

        @Test
        @DisplayName("records nothing")
        void offModeCountsNothing() throws Exception {
            offFilter().doFilter(request("OPTIONS", AUDITED_PATH), new MockHttpServletResponse(),
                    new MockFilterChain());

            assertThat(meterRegistry.find("http.method.rejected").counter()).isNull();
        }
    }

    private DisallowedHttpMethodFilter filter() {
        return new DisallowedHttpMethodFilter(properties("ENFORCE"), meterRegistry);
    }

    private DisallowedHttpMethodFilter offFilter() {
        return new DisallowedHttpMethodFilter(properties("OFF"), meterRegistry);
    }

    /**
     * A downstream chain that writes {@code name: value}, so the response wrapper's behaviour can be
     * observed without standing up a dispatcher. The {@code res} it receives is the wrapper.
     */
    private static FilterChain setsHeader(String name, String value) {
        return (req, res) -> ((HttpServletResponse) res).setHeader(name, value);
    }

    private static MethodGuardProperties properties(String mode) {
        MethodGuardProperties props = new MethodGuardProperties();
        props.setMode(mode);
        props.init();
        return props;
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }
}
