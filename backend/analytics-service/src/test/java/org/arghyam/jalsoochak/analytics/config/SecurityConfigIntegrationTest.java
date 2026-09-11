package org.arghyam.jalsoochak.analytics.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the anonymous surface of analytics-service.
 *
 * <p>{@code /api/v1/analytics/**} used to be {@code permitAll} for both GET and PUT, which made
 * every analytics endpoint world-readable — including the officer-scoped reads — and allowed
 * unauthenticated state mutation. The allowlist replaced that wildcard, so these tests assert both
 * halves of the split: the endpoints the public dashboard renders stay open, and everything else
 * under the same prefix does not.
 */
@WebMvcTest(controllers = SecuritySmokeController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an allowlisted analytics endpoint is readable without a token")
    void getAllowlistedAnalyticsEndpointPermittedWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/submission-status"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an analytics endpoint that is not allowlisted requires a token")
    void getUnlistedAnalyticsEndpointRequiresAuthentication() throws Exception {
        // The wildcard that used to make this anonymous is the audit finding itself. A new
        // analytics endpoint must now be added to PUBLIC_ANALYTICS_ENDPOINTS to be public,
        // rather than being world-readable the moment someone writes it.
        mockMvc.perform(get("/api/v1/analytics/smoke"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a non-GET method on an allowlisted analytics endpoint requires a token")
    void putOnAllowlistedAnalyticsEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/analytics/submission-status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("non-analytics endpoints require a token")
    void nonAnalyticsEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/internal/smoke"))
                .andExpect(status().isUnauthorized());
    }
}
