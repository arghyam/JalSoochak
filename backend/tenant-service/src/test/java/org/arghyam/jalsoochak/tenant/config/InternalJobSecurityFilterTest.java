package org.arghyam.jalsoochak.tenant.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalJobSecurityFilterTest {

    @Test
    void rejectsMissingToken() throws Exception {
        InternalJobSecurityFilter filter = new InternalJobSecurityFilter("expected-token", "/internal/");
        MockHttpServletRequest request = internalRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void allowsMatchingToken() throws Exception {
        InternalJobSecurityFilter filter = new InternalJobSecurityFilter("expected-token", "/internal/");
        MockHttpServletRequest request = internalRequest();
        request.addHeader("X-Internal-Token", "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void rejectsRequestsWhenSecretIsUnset() throws Exception {
        InternalJobSecurityFilter filter = new InternalJobSecurityFilter("", "/internal/");
        MockHttpServletRequest request = internalRequest();
        request.addHeader("X-Internal-Token", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest internalRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/jobs/nudge");
        request.setServletPath("/internal/jobs/nudge");
        return request;
    }
}