package org.arghyam.jalsoochak.telemetry.config;

import org.arghyam.jalsoochak.telemetry.service.TelemetryApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filter is the single fail-closed gate in front of the server-to-server reading routes.
 * These tests pin the two properties that matter: everything under the protected prefixes needs a
 * key unless it is on the explicit Glific webhook allowlist, and a rejected request never reaches
 * the handler.
 */
class TelemetryApiKeyAuthFilterTest {

    private static final String VALID_KEY = "js_valid_key";

    @Test
    void resetLatestWithoutApiKeyIsRejectedWithUnauthorizedAndNeverReachesTheHandler() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(post("/api/v1/telemetry/readings/reset-latest", null), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest(), "handler must not be invoked for an unauthenticated request");
        assertTrue(response.getContentAsString().contains("INVALID_API_KEY"));
        assertTrue(response.getContentType().startsWith("application/json"));
    }

    @Test
    void resetLatestWithUnknownApiKeyIsRejectedWithUnauthorized() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(post("/api/v1/telemetry/readings/reset-latest", "js_unknown_key"), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void resetLatestWithValidApiKeyPassesThroughAndPublishesTheResolvedTenant() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = post("/api/v1/telemetry/readings/reset-latest", VALID_KEY);

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
        assertEquals(22, request.getAttribute(TelemetryApiKeyAuthFilter.TENANT_ID_ATTRIBUTE));
    }

    @Test
    void everyRouteUnderReadingsIsProtectedByDefault() throws Exception {
        // A route that does not exist yet: the point of the prefix rule is that a future
        // /readings/** endpoint is authenticated without the author remembering to add a check.
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(post("/api/v1/telemetry/readings/some-future-endpoint", null), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void canonicalReadingsRoutesAreProtected() throws Exception {
        for (String path : new String[]{"/api/v1/telemetry/readings", "/api/v1/telemetry/readings/",
                "/api/v1/telemetry/readings/formats/assam"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(post(path, null), response, chain);

            assertEquals(401, response.getStatus(), path + " must require an API key");
            assertNull(chain.getRequest(), path + " must not reach the handler");
        }
    }

    @Test
    void yesterdayFinalReadingCorrectionIsProtected() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request =
                new MockHttpServletRequest("PATCH", "/api/v1/telemetry/schemes/42/yesterday-final-reading");

        filter().doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void glificWebhookRoutesStayUnauthenticated() throws Exception {
        // These are the WhatsApp webhook routes; they are unauthenticated by design today and are
        // tracked as a separate finding. Enforcing a key here would break the live bot.
        for (String path : new String[]{"/api/v1/telemetry/readings/glific", "/api/v1/telemetry/schemes",
                "/api/v1/telemetry/manual-reading", "/api/v1/telemetry/intro"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockHttpServletRequest request = post(path, null);

            filter().doFilter(request, response, chain);

            assertEquals(200, response.getStatus(), path + " must stay reachable without a key");
            assertEquals(request, chain.getRequest(), path + " must reach the handler");
            assertNull(request.getAttribute(TelemetryApiKeyAuthFilter.TENANT_ID_ATTRIBUTE));
        }
    }

    @Test
    void protectedPathIsMatchedAfterDecodingAndNormalisation() throws Exception {
        // Semicolon params, dot segments and duplicate slashes must not smuggle a request past the
        // prefix check: Spring normalises them before routing, so the filter has to as well.
        for (String path : new String[]{"/api/v1/telemetry/readings/reset-latest;jsessionid=abc",
                "/api/v1/telemetry/./readings/reset-latest",
                "/api/v1/telemetry//readings/reset-latest",
                "/api/v1/telemetry/readings/other/../reset-latest"}) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(post(path, null), response, chain);

            assertEquals(401, response.getStatus(), path + " must require an API key");
        }
    }

    @Test
    void unrelatedRoutesAreLeftAlone() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
    }

    @Test
    void rejectionBodyNeverEchoesTheSubmittedKey() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(post("/api/v1/telemetry/readings/reset-latest", "js_secret_probe"),
                response, new MockFilterChain());

        assertTrue(response.getContentAsString().length() < 512);
        assertTrue(!response.getContentAsString().contains("js_secret_probe"));
    }

    private static TelemetryApiKeyAuthFilter filter() {
        return new TelemetryApiKeyAuthFilter(new StubApiKeyService());
    }

    private static MockHttpServletRequest post(String uri, String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (apiKey != null) {
            request.addHeader("X-Api-Key", apiKey);
        }
        return request;
    }

    private static final class StubApiKeyService extends TelemetryApiKeyService {
        private StubApiKeyService() {
            super(null);
        }

        @Override
        public Optional<Integer> resolveTenantIdFromRawApiKey(String rawApiKey) {
            return VALID_KEY.equals(rawApiKey) ? Optional.of(22) : Optional.empty();
        }
    }
}
