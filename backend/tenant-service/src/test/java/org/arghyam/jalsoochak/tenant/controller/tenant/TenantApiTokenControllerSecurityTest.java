package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.dto.response.GenerateApiTokenResponseDTO;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security boundary tests for TenantApiTokenController.
 * Verifies that admin-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(TenantApiTokenController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("Tenant API Token Controller Security Tests")
class TenantApiTokenControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/v1/tenants/api-token — STATE_ADMIN only (tenant from JWT claim)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/tenants/api-token")
    class GenerateApiTokenSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void generateApiToken_Unauthenticated_Returns401() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/api-token"))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).generateApiToken(any(String.class));
        }

        @Test
        @DisplayName("STATE_ADMIN with tenant_state_code claim returns token")
        void generateApiToken_StateAdmin_WithClaim_Proceeds() throws Exception {
            when(tenantManagementService.generateApiToken("KA"))
                    .thenReturn(GenerateApiTokenResponseDTO.builder().token("js_somerawtoken").build());

            mockMvc.perform(post("/api/v1/tenants/api-token")
                    .with(jwt()
                            .authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))
                            .jwt(j -> j.claim("tenant_state_code", "KA"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("API token generated successfully"))
                    .andExpect(jsonPath("$.data.token").value("js_somerawtoken"));

            verify(tenantManagementService, times(1)).generateApiToken("KA");
        }

        @Test
        @DisplayName("STATE_ADMIN — tenant not found returns 404")
        void generateApiToken_TenantNotFound() throws Exception {
            when(tenantManagementService.generateApiToken(any(String.class)))
                    .thenThrow(new ResourceNotFoundException("Tenant not found for state code: XX"));

            mockMvc.perform(post("/api/v1/tenants/api-token")
                    .with(jwt()
                            .authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))
                            .jwt(j -> j.claim("tenant_state_code", "MP"))))
                    .andExpect(status().isNotFound());

            verify(tenantManagementService, times(1)).generateApiToken(any(String.class));
        }

        @Test
        @DisplayName("SUPER_USER is denied — 403")
        void generateApiToken_SuperUser_Returns403() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/api-token")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).generateApiToken(any(String.class));
        }
    }
}
