package org.arghyam.jalsoochak.scheme.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TenantInterceptorTest {

    private final TenantInterceptor interceptor = new TenantInterceptor();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void preHandle_setsTenantContextForValidHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Code", "Ka_01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(TenantContext.getSchema()).isEqualTo("tenant_ka_01");
    }

    @Test
    void preHandle_rejectsInvalidHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Code", "bad-code!");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    void afterCompletion_clearsTenantContext() {
        TenantContext.setSchema("tenant_ka");

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(TenantContext.getSchema()).isNull();
    }
}
