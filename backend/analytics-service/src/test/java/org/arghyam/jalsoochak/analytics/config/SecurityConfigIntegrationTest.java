package org.arghyam.jalsoochak.analytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecuritySmokeController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void getAnalyticsApiPermittedWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/smoke"))
                .andExpect(status().isOk());
    }

    @Test
    void nonAnalyticsEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/internal/smoke"))
                .andExpect(status().isUnauthorized());
    }
}
