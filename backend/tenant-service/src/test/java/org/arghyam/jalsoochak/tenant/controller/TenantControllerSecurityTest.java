package org.arghyam.jalsoochak.tenant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.config.TenantSecurityEvaluator;
import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.GenerateApiTokenResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Security boundary tests for TenantController.
 * Verifies that admin-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(TenantController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("Tenant Controller Security Tests")
class TenantControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @MockBean(name = "tenantSecurity")
    private TenantSecurityEvaluator tenantSecurityEvaluator;

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

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tenants/{id}/config — SUPER_USER or STATE_ADMIN
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tenants/{tenantId}/config")
    class GetTenantConfigSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void getTenantConfig_NoToken_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/1/config"))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).getTenantConfigs(anyInt(), any());
        }

        @Test
        @DisplayName("Unprivileged role returns 403")
        void getTenantConfig_UnprivilegedRole_Returns403() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/1/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing own tenant proceeds")
        void getTenantConfig_StateAdmin_OwnTenant_Proceeds() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(1)).thenReturn(true);
            when(tenantManagementService.getTenantConfigs(anyInt(), any()))
                    .thenReturn(TenantConfigResponseDTO.builder().tenantId(1).configs(Collections.emptyMap()).build());

            mockMvc.perform(get("/api/v1/tenants/1/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing a different tenant returns 403")
        void getTenantConfig_StateAdmin_DifferentTenant_Returns403() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(2)).thenReturn(false);

            mockMvc.perform(get("/api/v1/tenants/2/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).getTenantConfigs(anyInt(), any());
        }

        @Test
        @DisplayName("SUPER_USER role proceeds")
        void getTenantConfig_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.getTenantConfigs(anyInt(), any()))
                    .thenReturn(TenantConfigResponseDTO.builder().tenantId(1).configs(Collections.emptyMap()).build());

            mockMvc.perform(get("/api/v1/tenants/1/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/tenants/{id}/config/status — SUPER_USER or STATE_ADMIN
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/tenants/{tenantId}/config/status")
    class GetTenantConfigStatusSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void getTenantConfigStatus_NoToken_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/1/config/status"))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).getTenantConfigStatus(anyInt());
        }

        @Test
        @DisplayName("Unprivileged role returns 403")
        void getTenantConfigStatus_UnprivilegedRole_Returns403() throws Exception {
            mockMvc.perform(get("/api/v1/tenants/1/config/status")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing own tenant proceeds")
        void getTenantConfigStatus_StateAdmin_OwnTenant_Proceeds() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(1)).thenReturn(true);
            when(tenantManagementService.getTenantConfigStatus(anyInt()))
                    .thenReturn(TenantConfigStatusResponseDTO.builder().build());

            mockMvc.perform(get("/api/v1/tenants/1/config/status")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing a different tenant returns 403")
        void getTenantConfigStatus_StateAdmin_DifferentTenant_Returns403() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(2)).thenReturn(false);

            mockMvc.perform(get("/api/v1/tenants/2/config/status")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).getTenantConfigStatus(anyInt());
        }

        @Test
        @DisplayName("SUPER_USER role proceeds")
        void getTenantConfigStatus_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.getTenantConfigStatus(anyInt()))
                    .thenReturn(TenantConfigStatusResponseDTO.builder().build());

            mockMvc.perform(get("/api/v1/tenants/1/config/status")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/tenants/{id}/config — SUPER_USER or STATE_ADMIN
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/tenants/{tenantId}/config")
    class SetTenantConfigSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void setTenantConfig_NoToken_Returns401() throws Exception {
            String body = objectMapper.writeValueAsString(
                    SetTenantConfigRequestDTO.builder().configs(new HashMap<>()).build());

            mockMvc.perform(put("/api/v1/tenants/1/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isUnauthorized());

            verify(tenantManagementService, never()).setTenantConfigs(anyInt(), any());
        }

        @Test
        @DisplayName("Unprivileged role returns 403")
        void setTenantConfig_UnprivilegedRole_Returns403() throws Exception {
            Map<TenantConfigKeyEnum, JsonNode> cfgs = new HashMap<>();
            cfgs.put(TenantConfigKeyEnum.DATE_FORMAT_SCREEN, objectMapper.readTree("{\"format\":\"DD/MM/YYYY\"}"));
            String body = objectMapper.writeValueAsString(
                    SetTenantConfigRequestDTO.builder().configs(cfgs).build());

            mockMvc.perform(put("/api/v1/tenants/1/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PUMP_OPERATOR")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing own tenant proceeds")
        void setTenantConfig_StateAdmin_OwnTenant_Proceeds() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(1)).thenReturn(true);
            when(tenantManagementService.setTenantConfigs(anyInt(), any()))
                    .thenReturn(TenantConfigResponseDTO.builder().tenantId(1).configs(Collections.emptyMap()).build());

            Map<TenantConfigKeyEnum, JsonNode> cfgs = new HashMap<>();
            cfgs.put(TenantConfigKeyEnum.DATE_FORMAT_SCREEN, objectMapper.readTree("{\"format\":\"DD/MM/YYYY\"}"));
            String body = objectMapper.writeValueAsString(
                    SetTenantConfigRequestDTO.builder().configs(cfgs).build());

            mockMvc.perform(put("/api/v1/tenants/1/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("STATE_ADMIN accessing a different tenant returns 403")
        void setTenantConfig_StateAdmin_DifferentTenant_Returns403() throws Exception {
            when(tenantSecurityEvaluator.isOwnTenant(2)).thenReturn(false);

            Map<TenantConfigKeyEnum, JsonNode> cfgs = new HashMap<>();
            cfgs.put(TenantConfigKeyEnum.DATE_FORMAT_SCREEN, objectMapper.readTree("{\"format\":\"DD/MM/YYYY\"}"));
            String body = objectMapper.writeValueAsString(
                    SetTenantConfigRequestDTO.builder().configs(cfgs).build());

            mockMvc.perform(put("/api/v1/tenants/2/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isForbidden());

            verify(tenantManagementService, never()).setTenantConfigs(anyInt(), any());
        }

        @Test
        @DisplayName("SUPER_USER role proceeds")
        void setTenantConfig_SuperUser_Proceeds() throws Exception {
            when(tenantManagementService.setTenantConfigs(anyInt(), any()))
                    .thenReturn(TenantConfigResponseDTO.builder().tenantId(1).configs(Collections.emptyMap()).build());

            Map<TenantConfigKeyEnum, JsonNode> cfgs = new HashMap<>();
            cfgs.put(TenantConfigKeyEnum.DATE_FORMAT_SCREEN, objectMapper.readTree("{\"format\":\"DD/MM/YYYY\"}"));
            String body = objectMapper.writeValueAsString(
                    SetTenantConfigRequestDTO.builder().configs(cfgs).build());

            mockMvc.perform(put("/api/v1/tenants/1/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk());
        }
    }

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
