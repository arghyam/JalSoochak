package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantInterceptorTest {

    @Test
    void preHandleSetsSchemaFromHeaderAndAfterCompletionClearsIt() throws Exception {
        TenantInterceptor interceptor = new TenantInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Tenant-Code", "MP");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals("tenant_mp", TenantContext.getSchema());

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(TenantContext.getSchema());
    }

    @Test
    void preHandleRejectsMalformedTenantCode() throws Exception {
        TenantInterceptor interceptor = new TenantInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Tenant-Code", "MP!@#$");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(400, response.getStatus());
    }
}
