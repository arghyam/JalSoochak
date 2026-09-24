package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.config.TenantSecurityEvaluator;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security boundary tests for TenantBrandingController.
 * Verifies that admin-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(TenantBrandingController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("Tenant Branding Controller Security Tests")
class TenantBrandingControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @MockBean(name = "tenantSecurity")
    private TenantSecurityEvaluator tenantSecurityEvaluator;

    // ──────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/tenants/{id}/logo — SUPER_USER or STATE_ADMIN
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/tenants/{tenantId}/logo")
    class SetTenantLogoSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void setTenantLogo_NoToken_Returns401() throws Exception {
            mockMvc.perform(multipart("/api/v1/tenants/1/logo")
                    .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1}))
                    .with(request -> { request.setMethod("PUT"); return request; }))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).setTenantLogo(anyInt(), any());
        }

        @Test
        @DisplayName("Unprivileged role returns 403")
        void setTenantLogo_UnprivilegedRole_Returns403() throws Exception {
            mockMvc.perform(multipart("/api/v1/tenants/1/logo")
                    .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1}))
                    .with(request -> { request.setMethod("PUT"); return request; })
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing own tenant proceeds")
        void setTenantLogo_StateAdmin_OwnTenant_Proceeds() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(1)).thenReturn(true);
            when(tenantManagementService.setTenantLogo(anyInt(), any()))
                    .thenReturn(TenantConfigResponseDTO.builder().tenantId(1).configs(Collections.emptyMap()).build());

            mockMvc.perform(multipart("/api/v1/tenants/1/logo")
                    .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1}))
                    .with(request -> { request.setMethod("PUT"); return request; })
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing a different tenant returns 403")
        void setTenantLogo_StateAdmin_DifferentTenant_Returns403() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(2)).thenReturn(false);

            mockMvc.perform(multipart("/api/v1/tenants/2/logo")
                    .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1}))
                    .with(request -> { request.setMethod("PUT"); return request; })
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).setTenantLogo(anyInt(), any());
        }

        @Test
        @DisplayName("SUPER_USER role proceeds")
        void setTenantLogo_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.setTenantLogo(anyInt(), any()))
                    .thenReturn(TenantConfigResponseDTO.builder().tenantId(1).configs(Collections.emptyMap()).build());

            mockMvc.perform(multipart("/api/v1/tenants/1/logo")
                    .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1}))
                    .with(request -> { request.setMethod("PUT"); return request; })
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }
}
