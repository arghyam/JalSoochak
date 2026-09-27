package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.config.TenantSecurityEvaluator;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
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
 * Security boundary tests for TenantLocationController.
 * Verifies that admin-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(TenantLocationController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("Tenant Location Controller Security Tests")
class TenantLocationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @MockBean(name = "tenantSecurity")
    private TenantSecurityEvaluator tenantSecurityEvaluator;

    @Autowired
    private ObjectMapper objectMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tenants/{id}/location-hierarchy/{type}/edit-constraints
    // — SUPER_USER or STATE_ADMIN
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints")
    class GetLocationHierarchyEditConstraintsSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void getEditConstraints_NoToken_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/1/location-hierarchy/LGD/edit-constraints"))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).getLocationHierarchyEditConstraints(anyInt(), any());
        }

        @Test
        @DisplayName("Unprivileged role returns 403")
        void getEditConstraints_UnprivilegedRole_Returns403() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/1/location-hierarchy/LGD/edit-constraints")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing own tenant proceeds")
        void getEditConstraints_StateAdmin_OwnTenant_Proceeds() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(1)).thenReturn(true);
            when(tenantManagementService.getLocationHierarchyEditConstraints(anyInt(), any()))
                    .thenReturn(LocationHierarchyEditConstraintsResponseDTO.builder().build());

            mockMvc.perform(get("/api/v1/tenants/1/location-hierarchy/LGD/edit-constraints")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing a different tenant returns 403")
        void getEditConstraints_StateAdmin_DifferentTenant_Returns403() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(2)).thenReturn(false);

            mockMvc.perform(get("/api/v1/tenants/2/location-hierarchy/LGD/edit-constraints")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).getLocationHierarchyEditConstraints(anyInt(), any());
        }

        @Test
        @DisplayName("SUPER_USER role proceeds")
        void getEditConstraints_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.getLocationHierarchyEditConstraints(anyInt(), any()))
                    .thenReturn(LocationHierarchyEditConstraintsResponseDTO.builder().build());

            mockMvc.perform(get("/api/v1/tenants/1/location-hierarchy/LGD/edit-constraints")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/tenants/{id}/location-hierarchy/{type} — SUPER_USER or STATE_ADMIN
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}")
    class UpdateLocationHierarchySecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void updateLocationHierarchy_NoToken_Returns401() throws Exception {
            String body = objectMapper.writeValueAsString(List.of(LocationLevelConfigDTO.builder().build()));

            mockMvc.perform(put("/api/v1/tenants/1/location-hierarchy/LGD")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).updateLocationHierarchy(anyInt(), any(), any());
        }

        @Test
        @DisplayName("Unprivileged role returns 403")
        void updateLocationHierarchy_UnprivilegedRole_Returns403() throws Exception {
            String body = objectMapper.writeValueAsString(List.of(LocationLevelConfigDTO.builder().build()));

            mockMvc.perform(put("/api/v1/tenants/1/location-hierarchy/LGD")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing own tenant proceeds")
        void updateLocationHierarchy_StateAdmin_OwnTenant_Proceeds() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(1)).thenReturn(true);
            when(tenantManagementService.updateLocationHierarchy(anyInt(), any(), any()))
                    .thenReturn(LocationHierarchyResponseDTO.builder().build());

            String body = objectMapper.writeValueAsString(List.of(LocationLevelConfigDTO.builder().build()));

            mockMvc.perform(put("/api/v1/tenants/1/location-hierarchy/LGD")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing a different tenant returns 403")
        void updateLocationHierarchy_StateAdmin_DifferentTenant_Returns403() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(2)).thenReturn(false);

            String body = objectMapper.writeValueAsString(List.of(LocationLevelConfigDTO.builder().build()));

            mockMvc.perform(put("/api/v1/tenants/2/location-hierarchy/LGD")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).updateLocationHierarchy(anyInt(), any(), any());
        }

        @Test
        @DisplayName("SUPER_USER role proceeds")
        void updateLocationHierarchy_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.updateLocationHierarchy(anyInt(), any(), any()))
                    .thenReturn(LocationHierarchyResponseDTO.builder().build());

            String body = objectMapper.writeValueAsString(List.of(LocationLevelConfigDTO.builder().build()));

            mockMvc.perform(put("/api/v1/tenants/1/location-hierarchy/LGD")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk());
        }
    }
}
