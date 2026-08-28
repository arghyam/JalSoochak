package org.arghyam.jalsoochak.analytics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-request MDC context for the analytics logs.
 *
 * <p>The MDC keys are set for the duration of the request and cleared afterwards — a leak would let
 * one request's tenant id follow the thread into the next request's log lines, which in a pooled
 * container means attributing one tenant's activity to another.</p>
 */
@DisplayName("RequestContextLoggingFilter")
class RequestContextLoggingFilterTest {

    private final RequestContextLoggingFilter filter = new RequestContextLoggingFilter();

    /** Captures the MDC as it stood while the downstream chain ran. */
    private static class MdcCapturingChain implements FilterChain {
        private Map<String, String> mdcDuringRequest;
        private final int status;

        MdcCapturingChain(int status) {
            this.status = status;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            Map<String, String> copy = MDC.getCopyOfContextMap();
            mdcDuringRequest = copy == null ? new HashMap<>() : new HashMap<>(copy);
            ((MockHttpServletResponse) response).setStatus(status);
        }
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private Map<String, String> runFilter(MockHttpServletRequest request, int status)
            throws ServletException, IOException {
        MdcCapturingChain chain = new MdcCapturingChain(status);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.mdcDuringRequest;
    }

    @Nested
    @DisplayName("request id")
    class RequestId {

        @Test
        void reusesTheInboundRequestIdHeader() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenants");
            request.addHeader("X-Request-Id", "req-123");

            assertThat(runFilter(request, 200)).containsEntry("request_id", "req-123");
        }

        @Test
        void fallsBackToTheCorrelationIdHeader() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenants");
            request.addHeader("X-Correlation-Id", "corr-456");

            assertThat(runFilter(request, 200)).containsEntry("request_id", "corr-456");
        }

        @Test
        void prefersTheRequestIdHeaderOverTheCorrelationId() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenants");
            request.addHeader("X-Request-Id", "req-123");
            request.addHeader("X-Correlation-Id", "corr-456");

            assertThat(runFilter(request, 200)).containsEntry("request_id", "req-123");
        }

        @Test
        void ignoresABlankInboundHeaderAndGeneratesOne() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenants");
            request.addHeader("X-Request-Id", "   ");

            String generated = runFilter(request, 200).get("request_id");

            assertThat(generated).isNotBlank();
            assertThat(UUID.fromString(generated)).isNotNull();
        }

        @Test
        void generatesARequestIdWhenNoHeaderIsSent() throws Exception {
            String generated = runFilter(request("GET", "/api/v1/analytics/tenants"), 200).get("request_id");

            assertThat(generated).isNotBlank();
            assertThat(UUID.fromString(generated)).isNotNull();
        }
    }

    @Nested
    @DisplayName("request context")
    class RequestContext {

        @Test
        void recordsTheMethodAndPath() throws Exception {
            Map<String, String> mdc = runFilter(request("POST", "/api/v1/analytics/tenants"), 200);

            assertThat(mdc)
                    .containsEntry("http_method", "POST")
                    .containsEntry("http_path", "/api/v1/analytics/tenants");
        }

        @Test
        void recordsTheQueryStringWhenPresent() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenant_data");
            request.setQueryString("tenant_id=1&parent_lgd_id=101");

            assertThat(runFilter(request, 200))
                    .containsEntry("http_query", "tenant_id=1&parent_lgd_id=101");
        }

        @Test
        void omitsAnAbsentOrBlankQueryString() throws Exception {
            assertThat(runFilter(request("GET", "/api/v1/analytics/tenants"), 200))
                    .doesNotContainKey("http_query");

            MockHttpServletRequest blank = request("GET", "/api/v1/analytics/tenants");
            blank.setQueryString("   ");
            assertThat(runFilter(blank, 200)).doesNotContainKey("http_query");
        }

        @Test
        void recordsTheTenantFromTheQueryParameter() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenant_data");
            request.setParameter("tenant_id", "17");

            assertThat(runFilter(request, 200)).containsEntry("tenant_id", "17");
        }

        @Test
        void fallsBackToTheTenantHeader() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenant_data");
            request.addHeader("tenant_id", "17");

            assertThat(runFilter(request, 200)).containsEntry("tenant_id", "17");
        }

        @Test
        void omitsTheTenantWhenNeitherIsSupplied() throws Exception {
            assertThat(runFilter(request("GET", "/api/v1/analytics/tenants"), 200))
                    .doesNotContainKey("tenant_id");
        }
    }

    @Nested
    @DisplayName("context cleanup")
    class Cleanup {

        @Test
        void clearsEveryMdcKeyAfterASuccessfulRequest() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenant_data");
            request.setQueryString("tenant_id=17");
            request.setParameter("tenant_id", "17");

            runFilter(request, 200);

            assertThat(MDC.get("request_id")).isNull();
            assertThat(MDC.get("http_method")).isNull();
            assertThat(MDC.get("http_path")).isNull();
            assertThat(MDC.get("http_query")).isNull();
            assertThat(MDC.get("tenant_id")).isNull();
        }

        @Test
        void clearsEveryMdcKeyAfterAFailedRequest() {
            FilterChain exploding = (req, res) -> {
                throw new ServletException("handler blew up");
            };

            assertThatThrownBy(() -> filter.doFilter(
                    request("GET", "/api/v1/analytics/tenants"), new MockHttpServletResponse(), exploding))
                    .isInstanceOf(ServletException.class);

            // A leaked tenant_id would be attributed to whatever request reuses this pooled thread.
            assertThat(MDC.get("request_id")).isNull();
            assertThat(MDC.get("tenant_id")).isNull();
        }
    }

    @Nested
    @DisplayName("status logging")
    class StatusLogging {

        @Test
        void completesForAClientErrorStatus() throws Exception {
            assertThat(runFilter(request("GET", "/api/v1/analytics/tenants"), 400)).isNotNull();
        }

        @Test
        void completesForAServerErrorStatus() throws Exception {
            MockHttpServletRequest request = request("GET", "/api/v1/analytics/tenants");
            request.setQueryString("tenant_id=17");

            assertThat(runFilter(request, 500)).isNotNull();
        }

        @Test
        void completesForASuccessStatus() throws Exception {
            assertThat(runFilter(request("GET", "/api/v1/analytics/tenants"), 200)).isNotNull();
        }
    }
}
