package org.arghyam.jalsoochak.user.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantInterceptor")
class TenantInterceptorTest {

    private TenantInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new TenantInterceptor();
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("preHandle — header absent")
    class NoHeader {

        @Test
        @DisplayName("returns true and does not set schema context")
        void noHeaderAllowed() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest();
            MockHttpServletResponse res = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, res, new Object());

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isNull();
        }
    }

    @Nested
    @DisplayName("preHandle — valid tenant code")
    class ValidHeader {

        @Test
        @DisplayName("sets schema in context and returns true")
        void setsSchemaContext() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Tenant-Code", "MP");
            MockHttpServletResponse res = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, res, new Object());

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_mp");
        }

        @Test
        @DisplayName("trims whitespace from header value")
        void trimsTenantCode() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Tenant-Code", "  TR  ");
            MockHttpServletResponse res = new MockHttpServletResponse();

            interceptor.preHandle(req, res, new Object());

            assertThat(TenantContext.getSchema()).isEqualTo("tenant_tr");
        }

        @Test
        @DisplayName("accepts alphanumeric and underscore codes")
        void acceptsAlphanumericUnderscore() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Tenant-Code", "STATE_01");
            MockHttpServletResponse res = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, res, new Object());

            assertThat(result).isTrue();
            assertThat(TenantContext.getSchema()).isEqualTo("tenant_state_01");
        }
    }

    @Nested
    @DisplayName("preHandle — invalid tenant code format")
    class InvalidHeader {

        @Test
        @DisplayName("returns false and sends 400 for code with special chars")
        void rejectsSpecialChars() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Tenant-Code", "bad code!");
            MockHttpServletResponse res = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, res, new Object());

            assertThat(result).isFalse();
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        }

        @Test
        @DisplayName("returns false and sends 400 for code exceeding 32 chars")
        void rejectsLongCode() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader("X-Tenant-Code", "a".repeat(33));
            MockHttpServletResponse res = new MockHttpServletResponse();

            boolean result = interceptor.preHandle(req, res, new Object());

            assertThat(result).isFalse();
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("afterCompletion")
    class AfterCompletion {

        @Test
        @DisplayName("clears schema from context")
        void clearsTenantContext() throws Exception {
            TenantContext.setSchema("tenant_mp");

            interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

            assertThat(TenantContext.getSchema()).isNull();
        }
    }
}
