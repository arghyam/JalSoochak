package org.arghyam.jalsoochak.user.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RequestCorrelationFilter")
class RequestCorrelationFilterTest {

    private RequestCorrelationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestCorrelationFilter();
    }

    @Test
    @DisplayName("propagates provided X-Request-Id to the response header")
    void propagatesExistingRequestId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "my-correlation-id");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("my-correlation-id");
        verify(chain).doFilter(req, res);
    }

    @Test
    @DisplayName("generates a UUID request-id when header is absent")
    void generatesRequestIdWhenAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        String generatedId = res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(generatedId).isNotBlank();
        // UUID format
        assertThat(generatedId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("generates a new UUID when header is blank")
    void generatesRequestIdWhenBlank() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "  ");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        String generatedId = res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(generatedId).isNotBlank().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("always calls the filter chain")
    void alwaysCallsFilterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
