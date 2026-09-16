package org.arghyam.jalsoochak.user.config;

import jakarta.servlet.FilterChain;
import org.arghyam.jalsoochak.user.config.properties.PublicApiGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("PublicApiEnumerationGuardFilter Tests")
class PublicApiEnumerationGuardFilterTest {

    private static final String BY_SCHEME = "/api/v1/pumpoperator/pump-operators/by-scheme";
    private static final String BY_UUID = "/api/v1/pumpoperator/pump-operators/by-uuid/";

    private PublicApiGuardProperties properties;
    private PublicApiEnumerationGuardFilter filter;

    @BeforeEach
    void setUp() {
        properties = new PublicApiGuardProperties();
        properties.setWarnDistinctEntities(3);
        properties.setMaxDistinctEntities(5);
        filter = new PublicApiEnumerationGuardFilter(properties);
    }

    private MockHttpServletRequest schemeRequest(String tenantCode, long schemeId, String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BY_SCHEME);
        request.setRequestURI(BY_SCHEME);
        request.setParameter("tenantCode", tenantCode);
        request.setParameter("schemeId", String.valueOf(schemeId));
        request.setRemoteAddr(clientIp);
        return request;
    }

    private MockHttpServletResponse walk(String tenantCode, int fromScheme, int toScheme, String clientIp)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int schemeId = fromScheme; schemeId <= toScheme; schemeId++) {
            response = new MockHttpServletResponse();
            filter.doFilter(schemeRequest(tenantCode, schemeId, clientIp), response, new MockFilterChain());
        }
        return response;
    }

    @Nested
    @DisplayName("Legitimate traffic")
    class LegitimateTraffic {

        @Test
        @DisplayName("passes a caller reading the same scheme repeatedly")
        void allowsRepeatedReadsOfOneScheme() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            for (int i = 0; i < 50; i++) {
                response = new MockHttpServletResponse();
                filter.doFilter(schemeRequest("AS", 42L, "10.0.0.1"), response, new MockFilterChain());
            }

            // 50 requests, one distinct entity — a villager refreshing a page, not a scraper.
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("does not filter requests outside the public pump-operator tree")
        void ignoresUnrelatedPaths() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
            request.setRequestURI("/api/v1/auth/login");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }

        @Test
        @DisplayName("passes everything through when disabled")
        void offSwitchDisablesGuard() {
            properties.setEnabled(false);
            MockHttpServletRequest request = schemeRequest("AS", 1L, "10.0.0.1");

            assertThat(filter.shouldNotFilter(request)).isTrue();
        }
    }

    @Nested
    @DisplayName("Enumeration")
    class Enumeration {

        @Test
        @DisplayName("rejects a caller walking distinct scheme ids past the ceiling")
        void blocksSequentialWalk() throws Exception {
            MockHttpServletResponse response = walk("AS", 1, 20, "10.0.0.2");

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        }

        @Test
        @DisplayName("stops the chain when a request is rejected")
        void doesNotForwardRejectedRequest() throws Exception {
            walk("AS", 1, 20, "10.0.0.3");

            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(schemeRequest("AS", 999L, "10.0.0.3"), new MockHttpServletResponse(), chain);

            verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("counts the same scheme id in another tenant as a separate entity")
        void crossTenantWalkSpendsTheSameBudget() throws Exception {
            // Three ids in AS, then the same three in UP: six distinct entities, past the ceiling of
            // five. Switching tenantCode must not hand the caller a fresh budget.
            walk("AS", 1, 3, "10.0.0.4");
            MockHttpServletResponse response = walk("UP", 1, 3, "10.0.0.4");

            assertThat(response.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("counts distinct operator uuids")
        void blocksUuidWalk() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            for (int i = 0; i < 20; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", BY_UUID + i);
                request.setRequestURI(BY_UUID + "0000000" + i + "-0000-4000-8000-000000000000");
                request.setParameter("tenantCode", "AS");
                request.setRemoteAddr("10.0.0.5");
                response = new MockHttpServletResponse();
                filter.doFilter(request, response, new MockFilterChain());
            }

            assertThat(response.getStatus()).isEqualTo(429);
        }

        @Test
        @DisplayName("keeps one client's budget separate from another's")
        void budgetIsPerClient() throws Exception {
            walk("AS", 1, 20, "10.0.0.6");
            MockHttpServletResponse other = walk("AS", 1, 2, "10.0.0.7");

            assertThat(other.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("observes without rejecting when blocking is off")
        void observeOnlyModeDoesNotReject() throws Exception {
            properties.setBlocking(false);

            MockHttpServletResponse response = walk("AS", 1, 20, "10.0.0.8");

            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("does not merge two clients whose addresses share a 32-bit hash")
        void clientsWithCollidingHashCodesKeepSeparateBudgets() throws Exception {
            // "Aa" and "BB" have identical String.hashCode values. The client key was once that
            // hash, so a pair like this shared one budget and an ordinary caller could be turned
            // away for a scraper it never met. Over 50,000 tracked clients that collision was about
            // a one-in-four event, so this is the shape of a real false positive, not a curiosity.
            assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());

            walk("AS", 1, 20, "Aa");
            MockHttpServletResponse other = walk("AS", 1, 2, "BB");

            assertThat(other.getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Path matching")
    class PathMatching {

        private MockHttpServletRequest underContextPath(long schemeId) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user" + BY_SCHEME);
            request.setContextPath("/user");
            request.setRequestURI("/user" + BY_SCHEME);
            request.setParameter("tenantCode", "AS");
            request.setParameter("schemeId", String.valueOf(schemeId));
            request.setRemoteAddr("10.0.0.9");
            return request;
        }

        @Test
        @DisplayName("still guards the public tree when deployed under a servlet context path")
        void stripsContextPathBeforeMatching() throws Exception {
            // Comparing the raw URI would make every path miss the prefix, so the guard would stop
            // firing entirely -- a security control failing open on a deployment setting.
            assertThat(filter.shouldNotFilter(underContextPath(1L))).isFalse();

            MockHttpServletResponse response = new MockHttpServletResponse();
            for (int schemeId = 1; schemeId <= 20; schemeId++) {
                response = new MockHttpServletResponse();
                filter.doFilter(underContextPath(schemeId), response, new MockFilterChain());
            }

            assertThat(response.getStatus()).isEqualTo(429);
        }
    }
}
