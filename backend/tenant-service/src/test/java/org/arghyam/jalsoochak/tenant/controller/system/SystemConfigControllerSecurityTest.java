package org.arghyam.jalsoochak.tenant.controller.system;

import static org.mockito.ArgumentMatchers.any;
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
import org.arghyam.jalsoochak.tenant.dto.request.SetSystemConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.SystemConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
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
 * Security boundary tests for SystemConfigController.
 * Verifies that SUPER_USER-only endpoints enforce authentication and role authorization.
 * Filters are enabled (no addFilters = false) to exercise the real security chain.
 */
@WebMvcTest(SystemConfigController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityExceptionHandler.class})
@DisplayName("System Config Controller Security Tests")
class SystemConfigControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SystemManagementService systemManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/v1/system/config — SUPER_USER only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/system/config")
    class GetSystemConfigSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void getSystemConfig_NoToken_Returns401() throws Exception {
            mockMvc.perform(get("/api/v1/system/config"))
                    .andExpect(status().isUnauthorized());

            verify(systemManagementService, never()).getSystemConfigs(any());
        }

        @Test
        @DisplayName("STATE_ADMIN role is forbidden")
        void getSystemConfig_StateAdmin_Returns403() throws Exception {
            mockMvc.perform(get("/api/v1/system/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN"))))
                    .andExpect(status().isForbidden());

            verify(systemManagementService, never()).getSystemConfigs(any());
        }

        @Test
        @DisplayName("SUPER_USER role reaches the service layer")
        void getSystemConfig_SuperUser_Proceeds() throws Exception {
            when(systemManagementService.getSystemConfigs(any()))
                    .thenReturn(SystemConfigResponseDTO.builder()
                            .configs(Collections.emptyMap())
                            .build());

            mockMvc.perform(get("/api/v1/system/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER"))))
                    .andExpect(status().isOk());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PUT /api/v1/system/config — SUPER_USER only
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/system/config")
    class SetSystemConfigSecurity {

        @Test
        @DisplayName("Unauthenticated request returns 401")
        void setSystemConfig_NoToken_Returns401() throws Exception {
            String body = objectMapper.writeValueAsString(
                    SetSystemConfigRequestDTO.builder().configs(new HashMap<>()).build());

            mockMvc.perform(put("/api/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isUnauthorized());

            verify(systemManagementService, never()).setSystemConfigs(any());
        }

        @Test
        @DisplayName("STATE_ADMIN role is forbidden")
        void setSystemConfig_StateAdmin_Returns403() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> cfgs = new HashMap<>();
            cfgs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));
            String body = objectMapper.writeValueAsString(
                    SetSystemConfigRequestDTO.builder().configs(cfgs).build());

            mockMvc.perform(put("/api/v1/system/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STATE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isForbidden());

            verify(systemManagementService, never()).setSystemConfigs(any());
        }

        @Test
        @DisplayName("SUPER_USER role reaches the service layer")
        void setSystemConfig_SuperUser_Proceeds() throws Exception {
            when(systemManagementService.setSystemConfigs(any()))
                    .thenReturn(SystemConfigResponseDTO.builder()
                            .configs(Collections.emptyMap())
                            .build());

            Map<SystemConfigKeyEnum, JsonNode> cfgs = new HashMap<>();
            cfgs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));
            String body = objectMapper.writeValueAsString(
                    SetSystemConfigRequestDTO.builder().configs(cfgs).build());

            mockMvc.perform(put("/api/v1/system/config")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPER_USER")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk());
        }
    }
}
