package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Security boundary tests for TenantLifecycleController.
 * Verifies that admin-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(TenantLifecycleController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("Tenant Lifecycle Controller Security Tests")
class TenantLifecycleControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/v1/tenants — SUPER_USER only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/tenants")
    class CreateTenantSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void createTenant_NoToken_Returns401() throws Exception {
            mockMvc.perform(post("/api/v1/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).createTenant(any());
        }

        @Test
        @DisplayName("STATE_ADMIN role is forbidden")
        void createTenant_StateAdmin_Returns403() throws Exception {
            String body = objectMapper.writeValueAsString(
                    CreateTenantRequestDTO.builder()
                            .name("Test Tenant")
                            .stateCode("TS")
                            .lgdCode(1)
                            .build());

            mockMvc.perform(post("/api/v1/tenants")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).createTenant(any());
        }

        @Test
        @DisplayName("SUPER_USER role reaches the service layer")
        void createTenant_SuperUser_Proceeds() throws Exception {
            TenantResponseDTO dto = TenantResponseDTO.builder().build();
            when(tenantManagementService.createTenant(any())).thenReturn(dto);

            String body = objectMapper.writeValueAsString(
                    CreateTenantRequestDTO.builder()
                            .name("Test Tenant")
                            .stateCode("TS")
                            .lgdCode(1)
                            .build());

            mockMvc.perform(post("/api/v1/tenants")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().is2xxSuccessful());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tenants/summary — SUPER_USER only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tenants/summary")
    class GetTenantSummarySecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void getTenantSummary_NoToken_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/summary"))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).getTenantSummary();
        }

        @Test
        @DisplayName("STATE_ADMIN role is forbidden")
        void getTenantSummary_StateAdmin_Returns403() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/summary")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SUPER_USER role reaches the service layer")
        void getTenantSummary_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.getTenantSummary())
                    .thenReturn(TenantSummaryResponseDTO.builder().build());

            mockMvc.perform(get("/api/v1/tenants/summary")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/tenants/{id} — SUPER_USER only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/tenants/{tenantId}")
    class UpdateTenantSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void updateTenant_NoToken_Returns401() throws Exception {
            mockMvc.perform(put("/api/v1/tenants/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("STATE_ADMIN role is forbidden")
        void updateTenant_StateAdmin_Returns403() throws Exception {
            mockMvc.perform(put("/api/v1/tenants/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SUPER_USER role reaches the service layer")
        void updateTenant_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.updateTenant(anyInt(), any()))
                    .thenReturn(TenantResponseDTO.builder().build());

            String body = objectMapper.writeValueAsString(
                    UpdateTenantRequestDTO.builder().build());

            mockMvc.perform(put("/api/v1/tenants/1")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/v1/tenants/{id}/deactivate — SUPER_USER only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/tenants/{tenantId}/deactivate")
    class DeactivateTenantSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void deactivateTenant_NoToken_Returns401() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/1/deactivate"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("STATE_ADMIN role is forbidden")
        void deactivateTenant_StateAdmin_Returns403() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/1/deactivate")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SUPER_USER role reaches the service layer")
        void deactivateTenant_SuperUser_Proceeds() throws Exception {
            mockMvc.perform(post("/api/v1/tenants/1/deactivate")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tenants — public (no auth required)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tenants (public)")
    class GetAllTenantsSecurity {

        @Test
        @DisplayName("Unauthenticated request is allowed")
        void getAllTenants_NoToken_Returns200() throws Exception {
            when(tenantManagementService.getAllTenants(anyInt(), anyInt(), any(), any()))
                    .thenReturn(PageResponseDTO.<TenantResponseDTO>of(Collections.emptyList(), 0, 0, 10));

            mockMvc.perform(get("/api/v1/tenants"))
                    .andExpect(status().isOk());
        }
    }
}
