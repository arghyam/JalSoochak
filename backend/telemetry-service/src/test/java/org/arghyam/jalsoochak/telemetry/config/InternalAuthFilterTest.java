package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter is the only thing in front of the operations routes. These tests pin that everything
 * under {@code /api/v1/telemetry/internal} needs the token however the path is spelled, that nothing
 * else is touched, and that a rejected request never reaches the handler. That every internal
 * controller maps under that prefix is pinned in {@code WebhookRouteCoverageTest}.
 */
@DisplayName("InternalAuthFilter")
class InternalAuthFilterTest {

    private static final String TOKEN = "js_internal_test_token";
    private static final String REPUBLISH = "/api/v1/telemetry/internal/readings/republish";

    private static InternalAuthFilter filter(String tokenHash) {
        InternalAuthProperties properties = new InternalAuthProperties();
        properties.setTokenHash(tokenHash);
        properties.init();
        return new InternalAuthFilter(properties);
    }

    private static InternalAuthFilter enabledFilter() {
        return filter(WebhookAuthProperties.sha256Hex(TOKEN));
    }

    private static MockHttpServletRequest post(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (token != null) {
            request.addHeader(InternalAuthFilter.TOKEN_HEADER, token);
        }
        return request;
    }

    /** @return the response, after asserting the request was refused before reaching the handler */
    private static MockHttpServletResponse assertRejected(InternalAuthFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as(request.getRequestURI()).isEqualTo(401);
        assertThat(chain.getRequest()).as("%s must not reach the handler", request.getRequestURI()).isNull();
        return response;
    }

    private static void assertPassedThrough(InternalAuthFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as(request.getRequestURI()).isEqualTo(200);
        assertThat(chain.getRequest()).as("%s must reach the handler", request.getRequestURI()).isSameAs(request);
    }

    @Test
    @DisplayName("a request without the token gets a JSON 401")
    void rejectsAMissingToken() throws Exception {
        MockHttpServletResponse response = assertRejected(enabledFilter(), post(REPUBLISH, null));

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"success\":false,\"message\":\"Unauthorized\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"js_wrong_token", "   "})
    @DisplayName("a request with any other token gets 401")
    void rejectsAWrongToken(String token) throws Exception {
        assertRejected(enabledFilter(), post(REPUBLISH, token));
    }

    @Test
    @DisplayName("a request with the token reaches the handler")
    void passesTheRightToken() throws Exception {
        assertPassedThrough(enabledFilter(), post(REPUBLISH, TOKEN));
    }

    @Test
    @DisplayName("with no hash configured, every request gets 401, token or not")
    void rejectsEveryRequestWhileDisabled() throws Exception {
        InternalAuthFilter disabled = filter("");

        assertRejected(disabled, post(REPUBLISH, TOKEN));
        assertRejected(disabled, post(REPUBLISH, null));
    }

    @Test
    @DisplayName("a route not mapped yet is protected by the prefix")
    void protectsEveryRouteUnderThePrefix() throws Exception {
        assertRejected(enabledFilter(), post("/api/v1/telemetry/internal/some-future-endpoint", null));
        assertRejected(enabledFilter(), post("/api/v1/telemetry/internal", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/telemetry/internal/readings/republish;jsessionid=abc",
            "/api/v1/telemetry/./internal/readings/republish",
            "/api/v1/telemetry//internal/readings/republish",
            "/api/v1/telemetry/readings/../internal/readings/republish",
            "/api/v1/telemetry/%69nternal/readings/republish",
            "/api/v1/telemetry/INTERNAL/readings/republish",
            "/api/v1/telemetry/internal/readings/republish/"
    })
    @DisplayName("a protected path is matched however it is spelled")
    void matchesThePathAfterNormalisation(String path) throws Exception {
        assertRejected(enabledFilter(), post(path, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/telemetry/readings",
            "/api/v1/telemetry/intro",
            "/api/v1/telemetry/internals",
            "/actuator/health"
    })
    @DisplayName("every other path is left alone")
    void leavesOtherPathsAlone(String path) throws Exception {
        assertPassedThrough(enabledFilter(), post(path, null));
    }

    @Test
    @DisplayName("the rejection never echoes the submitted token")
    void rejectionNeverEchoesTheToken() throws Exception {
        MockHttpServletResponse response = assertRejected(enabledFilter(), post(REPUBLISH, "js_secret_probe"));

        assertThat(response.getContentAsString()).doesNotContain("js_secret_probe");
    }
}
