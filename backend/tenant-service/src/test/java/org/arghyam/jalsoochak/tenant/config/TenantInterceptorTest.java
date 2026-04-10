package org.arghyam.jalsoochak.tenant.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TenantInterceptor}.
 *
 * Verifies header parsing, schema name construction, injection-safe validation,
 * and ThreadLocal cleanup in {@code afterCompletion}.
 */
@DisplayName("TenantInterceptor Tests")
class TenantInterceptorTest {

    private static final String TENANT_HEADER = "X-Tenant-Code";

    private final TenantInterceptor interceptor = new TenantInterceptor();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // ── preHandle: header present ────────────────────────────────────────────────

    @Nested
    @DisplayName("preHandle – header present")
    class PreHandleWithHeader {

        @Test
        @DisplayName("uppercase tenant code is lowercased and prefixed as schema name")
        void validUppercaseTenantCode_setsLowercaseSchema() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "MP");

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_mp");
        }

        @Test
        @DisplayName("lowercase tenant code sets schema with tenant_ prefix")
        void validLowercaseTenantCode_setsSchema() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "tr");

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_tr");
        }

        @Test
        @DisplayName("alphanumeric tenant code with underscore sets schema correctly")
        void alphanumericWithUnderscoreTenantCode_setsSchema() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "UP_01");

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_up_01");
        }

        @Test
        @DisplayName("tenant code with leading/trailing whitespace is trimmed before validation")
        void tenantCodeWithWhitespace_isTrimmed() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "  MH  ");

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_mh");
        }

        @Test
        @DisplayName("SQL injection attempt in header returns 400 and does not set schema")
        void sqlInjectionInHeader_returns400() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            request.addHeader(TENANT_HEADER, "mp; DROP TABLE users");

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
            assertThat(TenantContext.getSchema()).isNull();
        }

        @Test
        @DisplayName("header with special characters returns 400 and does not set schema")
        void specialCharactersInHeader_returns400() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            request.addHeader(TENANT_HEADER, "mp'--");

            boolean result = interceptor.preHandle(request, response, null);

            assertThat(result).isFalse();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
            assertThat(TenantContext.getSchema()).isNull();
        }

        @Test
        @DisplayName("digit-prefixed tenant code is accepted and sets schema correctly")
        void digitPrefixedTenantCode_setsSchema() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "1MP");

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_1mp");
        }

        @Test
        @DisplayName("blank-only tenant code does not set schema but returns true")
        void blankOnlyTenantCode_doesNotSetSchema_returnsTrue() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "   ");

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isNull();
        }
    }

    // ── preHandle: header absent ─────────────────────────────────────────────────

    @Nested
    @DisplayName("preHandle – header absent")
    class PreHandleWithoutHeader {

        @Test
        @DisplayName("no tenant header does not set schema and returns true")
        void noHeader_returnsTrue_noSchemaSet() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();

            boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), null);

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isNull();
        }
    }

    // ── afterCompletion ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("afterCompletion")
    class AfterCompletion {

        @Test
        @DisplayName("afterCompletion always clears the tenant context")
        void afterCompletion_clearsTenantContext() throws Exception {
            TenantContext.setSchema("tenant_mp");

            interceptor.afterCompletion(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), null, null);

            assertThat(TenantContext.getSchema()).isNull();
        }

        @Test
        @DisplayName("afterCompletion clears context even when an exception is present")
        void afterCompletion_clearsTenantContext_withException() throws Exception {
            TenantContext.setSchema("tenant_mp");

            interceptor.afterCompletion(
                    new MockHttpServletRequest(), new MockHttpServletResponse(),
                    null, new RuntimeException("simulated handler error"));

            assertThat(TenantContext.getSchema()).isNull();
        }

        @Test
        @DisplayName("afterCompletion is safe to call when no schema was set")
        void afterCompletion_safeWhenNoSchemaSet() throws Exception {
            // should not throw
            interceptor.afterCompletion(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), null, null);

            assertThat(TenantContext.getSchema()).isNull();
        }
    }

    // ── full request lifecycle ───────────────────────────────────────────────────

    @Nested
    @DisplayName("full request lifecycle")
    class FullRequestLifecycle {

        @Test
        @DisplayName("schema set in preHandle is cleared in afterCompletion")
        void schemaSetThenCleared_acrossFullLifecycle() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TENANT_HEADER, "GJ");
            MockHttpServletResponse response = new MockHttpServletResponse();

            interceptor.preHandle(request, response, null);
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_gj");

            interceptor.afterCompletion(request, response, null, null);
            assertThat(TenantContext.getSchema()).isNull();
        }
    }
}
