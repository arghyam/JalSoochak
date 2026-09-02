package org.arghyam.jalsoochak.telemetry.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("GlificWebhookAuthFilter")
class GlificWebhookAuthFilterTest {

    private static final String TOKEN = "js_glific_webhook_token";
    private static final String TOKEN_HASH = WebhookAuthProperties.sha256Hex(TOKEN);
    private static final String HEADER = "X-Webhook-Token";
    private static final String PROTECTED_PATH = "/api/v1/telemetry/intro";

    private MeterRegistry meterRegistry;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        chain = Mockito.mock(FilterChain.class);
    }

    private static WebhookAuthProperties properties(String mode) {
        WebhookAuthProperties props = new WebhookAuthProperties();
        props.setMode(mode);
        props.setHeaderName(HEADER);
        props.setTokenHashes(TOKEN_HASH);
        props.init();
        return props;
    }

    private GlificWebhookAuthFilter filter(String mode) {
        return new GlificWebhookAuthFilter(properties(mode), meterRegistry);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private double counter(String result, String mode) {
        var counter = meterRegistry.find("telemetry.webhook.auth")
                .tag("result", result)
                .tag("mode", mode)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    @Nested
    @DisplayName("ENFORCE")
    class Enforce {

        @Test
        @DisplayName("serves a request carrying a valid token")
        void servesValidToken() throws Exception {
            MockHttpServletRequest request = request("POST", PROTECTED_PATH);
            request.addHeader(HEADER, TOKEN);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(counter("ok", "ENFORCE")).isEqualTo(1d);
        }

        @Test
        @DisplayName("rejects a request with no token and never reaches the handler")
        void rejectsMissingToken() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request("POST", PROTECTED_PATH), response, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(counter("missing", "ENFORCE")).isEqualTo(1d);
        }

        @Test
        @DisplayName("rejects a wrong token")
        void rejectsWrongToken() throws Exception {
            MockHttpServletRequest request = request("POST", PROTECTED_PATH);
            request.addHeader(HEADER, "not-the-token");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request, response, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(counter("invalid", "ENFORCE")).isEqualTo(1d);
        }

        @Test
        @DisplayName("returns JSON, not Tomcat's HTML error page, so Glific can parse the reply")
        void returnsParseableJson() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request("POST", PROTECTED_PATH), response, chain);

            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"success\":false,\"message\":\"Unauthorized\"}");
            // sendError would have set an error message and deferred to the container error page.
            assertThat(response.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("accepts either token during a rotation window")
        void acceptsRotatedToken() throws Exception {
            String next = "js_rotated";
            WebhookAuthProperties props = new WebhookAuthProperties();
            props.setHeaderName(HEADER);
            props.setTokenHashes(TOKEN_HASH + "," + WebhookAuthProperties.sha256Hex(next));
            props.init();
            GlificWebhookAuthFilter rotating = new GlificWebhookAuthFilter(props, meterRegistry);

            for (String token : new String[]{TOKEN, next}) {
                MockHttpServletRequest request = request("POST", PROTECTED_PATH);
                request.addHeader(HEADER, token);
                rotating.doFilter(request, new MockHttpServletResponse(), chain);
            }

            verify(chain, times(2)).doFilter(any(), any());
        }

        @Test
        @DisplayName("challenges every one of the 26 protected routes")
        void challengesEveryProtectedRoute() throws Exception {
            GlificWebhookAuthFilter filter = filter("ENFORCE");

            for (String path : GlificWebhookRoutes.absolutePaths()) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request("POST", path), response, chain);
                assertThat(response.getStatus()).as("unprotected route %s", path).isEqualTo(401);
            }
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("AUDIT — the kill switch")
    class Audit {

        @Test
        @DisplayName("serves an unauthenticated request but records it")
        void servesAndRecords() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("AUDIT").doFilter(request("POST", PROTECTED_PATH), response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(counter("missing", "AUDIT")).isEqualTo(1d);
        }

        @Test
        @DisplayName("still records a valid token, so the cutover can be gated on the metric")
        void recordsValidToken() throws Exception {
            MockHttpServletRequest request = request("POST", PROTECTED_PATH);
            request.addHeader(HEADER, TOKEN);

            filter("AUDIT").doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(counter("ok", "AUDIT")).isEqualTo(1d);
        }
    }

    @Nested
    @DisplayName("OFF")
    class Off {

        @Test
        @DisplayName("skips the check entirely and records nothing")
        void skipsCheck() throws Exception {
            WebhookAuthProperties props = new WebhookAuthProperties();
            props.setMode("OFF");
            props.setHeaderName(HEADER);
            props.setTokenHashes("");
            props.init();

            new GlificWebhookAuthFilter(props, meterRegistry)
                    .doFilter(request("POST", PROTECTED_PATH), new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
            assertThat(meterRegistry.find("telemetry.webhook.auth").counter()).isNull();
        }
    }

    @Nested
    @DisplayName("routes that must NOT be affected")
    class UnaffectedRoutes {

        /**
         * The auditor recommended a blanket rule on {@code /api/v1/telemetry/**}. These paths share
         * that prefix but authenticate with the per-tenant {@code X-Api-Key}, or are infrastructure.
         * A blanket rule would 401 all of them. This is the test that proves it was avoided.
         */
        @ParameterizedTest
        @CsvSource({
                "POST,  /api/v1/telemetry/readings",
                "POST,  /api/v1/telemetry/readings/",
                "PUT,   /api/v1/telemetry/readings",
                "POST,  /api/v1/telemetry/readings/reset-latest",
                "POST,  /api/v1/telemetry/readings/formats/assam",
                "PATCH, /api/v1/telemetry/schemes/7/yesterday-final-reading",
                "GET,   /api/v1/telemetry",
                "POST,  /api/v1/publish",
                "GET,   /actuator/health",
                "GET,   /actuator/prometheus",
                "GET,   /v3/api-docs",
                "GET,   /swagger-ui/index.html"
        })
        @DisplayName("pass through untouched with no token, even under ENFORCE")
        void ingestionAndInfrastructureUnaffected(String method, String path) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request(method, path), response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("a GET to a webhook path is not challenged; only POST is mapped")
        void onlyPostIsChallenged() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request("GET", PROTECTED_PATH), response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("path normalization — no smuggling past the allowlist")
    class Normalization {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/telemetry/intro",
                "/api/v1/telemetry/intro/",          // trailing slash
                "//api/v1/telemetry/intro",          // duplicated slash
                "/api/v1//telemetry/intro",
                "/api/v1/telemetry/./intro",         // current-directory segment
                "/api/v1/telemetry/foo/../intro",    // parent traversal
                "/api/v1/telemetry/%69ntro",         // percent-encoded 'i'
                "/api/v1/telemetry/intro;jsessionid=abc",  // path parameter
                "/api/v1/telemetry/intro;x=1/"
        })
        @DisplayName("every spelling of a protected route is still challenged")
        void variantsAreChallenged(String uri) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request("POST", uri), response, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus()).as("smuggled through: %s", uri).isEqualTo(401);
        }

        @Test
        @DisplayName("normalizes to the handler-visible path")
        void normalizesToHandlerPath() {
            assertThat(GlificWebhookAuthFilter.normalize("/api/v1/telemetry/foo/../intro/"))
                    .isEqualTo(PROTECTED_PATH);
            assertThat(GlificWebhookAuthFilter.normalize("/api/v1/telemetry/readings/formats/assam"))
                    .isEqualTo("/api/v1/telemetry/readings/formats/assam");
        }

        @Test
        @DisplayName("a request under a context path still matches")
        void honoursContextPath() throws Exception {
            MockHttpServletRequest request = request("POST", "/telemetry" + PROTECTED_PATH);
            request.setContextPath("/telemetry");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request, response, chain);

            verify(chain, never()).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("a malformed escape sequence does not crash the filter")
        void toleratesMalformedEscape() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("ENFORCE").doFilter(request("POST", "/api/v1/telemetry/int%ro"), response, chain);

            // Normalizes to something outside the allowlist, so it passes through to a 404 handler.
            verify(chain).doFilter(any(), any());
        }
    }

    @Nested
    @DisplayName("privacy of the credential")
    class CredentialPrivacy {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger logger;

        @BeforeEach
        void attachAppender() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            logger = context.getLogger(GlificWebhookAuthFilter.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.TRACE);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        @Test
        @DisplayName("never writes the token to the log, valid or not")
        void neverLogsTheToken() throws Exception {
            GlificWebhookAuthFilter filter = filter("ENFORCE");

            MockHttpServletRequest valid = request("POST", PROTECTED_PATH);
            valid.addHeader(HEADER, TOKEN);
            filter.doFilter(valid, new MockHttpServletResponse(), chain);

            MockHttpServletRequest wrong = request("POST", PROTECTED_PATH);
            wrong.addHeader(HEADER, "attacker-supplied-secret");
            filter.doFilter(wrong, new MockHttpServletResponse(), chain);

            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains(TOKEN))
                    .noneMatch(event -> event.getFormattedMessage().contains("attacker-supplied-secret"))
                    .noneMatch(event -> event.getFormattedMessage().contains(TOKEN_HASH));
        }
    }
}
