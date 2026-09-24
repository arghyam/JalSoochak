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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.config.JwtAuthConverter;
import org.arghyam.jalsoochak.tenant.config.SecurityConfig;
import org.arghyam.jalsoochak.tenant.config.SecurityExceptionHandler;
import org.arghyam.jalsoochak.tenant.config.TenantSecurityEvaluator;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Security boundary tests for TenantConfigController.
 * Verifies that admin-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(TenantConfigController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("Tenant Config Controller Security Tests")
class TenantConfigControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @MockBean(name = "tenantSecurity")
    private TenantSecurityEvaluator tenantSecurityEvaluator;

    @Autowired
    private ObjectMapper objectMapper;

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
}
