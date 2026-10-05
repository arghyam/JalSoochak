package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.ConfigStatusEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigKeyException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(TenantConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Tenant Config Controller Tests")
class TenantConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Get Tenant Configurations")
    class GetTenantConfigsTests {
        @Test
        void getTenantConfigs_Success() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, new SimpleConfigValueDTO("url"));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder().tenantId(tenantId).configs(configs).build();

            when(tenantManagementService.getTenantConfigs(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/tenants/" + tenantId + "/config")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.configs.TENANT_LOGO.value").value("url"));

            verify(tenantManagementService).getTenantConfigs(eq(tenantId), any());
        }

        @Test
        void getTenantConfigs_WithKeys() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, new SimpleConfigValueDTO("url"));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder().tenantId(tenantId).configs(configs).build();

            when(tenantManagementService.getTenantConfigs(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/tenants/" + tenantId + "/config")
                    .param("keys", "TENANT_LOGO")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configs.TENANT_LOGO.value").value("url"));
        }

        @Test
        void getTenantConfigs_NotFound() throws Exception {
            Integer tenantId = 999;
            when(tenantManagementService.getTenantConfigs(eq(tenantId), any()))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(get("/api/v1/tenants/" + tenantId + "/config"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void getTenantConfigs_InvalidKey_ReturnsBadRequest() throws Exception {
            Integer tenantId = 1;

            mockMvc.perform(get("/api/v1/tenants/" + tenantId + "/config")
                    .param("keys", "NOT_A_VALID_KEY"))
                    .andExpect(status().isBadRequest());

            verify(tenantManagementService, never()).getTenantConfigs(any(), any());
        }
    }

    @Nested
    @DisplayName("Set Tenant Configurations")
    class SetTenantConfigsTests {
        @Test
        void setTenantConfigs_Success() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, JsonNode> requestConfigs = new HashMap<>();
            requestConfigs.put(TenantConfigKeyEnum.WATER_NORM, objectMapper.readTree("{\"value\":\"55\"}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(requestConfigs).build();

            Map<TenantConfigKeyEnum, ConfigValueDTO> responseConfigs = new HashMap<>();
            responseConfigs.put(TenantConfigKeyEnum.WATER_NORM, new SimpleConfigValueDTO("55"));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder().tenantId(tenantId).configs(responseConfigs).build();

            when(tenantManagementService.setTenantConfigs(eq(tenantId), any(SetTenantConfigRequestDTO.class)))
                    .thenReturn(response);

            mockMvc.perform(put("/api/v1/tenants/" + tenantId + "/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.configs.WATER_NORM.value").value("55"));

            verify(tenantManagementService).setTenantConfigs(eq(tenantId), any());
        }

        @Test
        void setTenantConfigs_ManagedValueKey_ReturnsBadRequest() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, objectMapper.readTree("{\"value\":\"https://example.com/logo.png\"}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            when(tenantManagementService.setTenantConfigs(eq(tenantId), any()))
                    .thenThrow(new InvalidConfigKeyException(
                            "TENANT_LOGO is managed by a dedicated endpoint and cannot be set via the generic config API."));

            mockMvc.perform(put("/api/v1/tenants/" + tenantId + "/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void setTenantConfigs_InvalidConfigKey_ReturnsBadRequest() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON, objectMapper.readTree("{\"value\":\"template\"}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            when(tenantManagementService.setTenantConfigs(eq(tenantId), any()))
                    .thenThrow(new InvalidConfigKeyException("Unknown config key"));

            mockMvc.perform(put("/api/v1/tenants/" + tenantId + "/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void setTenantConfigs_NotFound() throws Exception {
            Integer tenantId = 999;
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.WATER_NORM, objectMapper.readTree("{\"value\":\"55\"}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            when(tenantManagementService.setTenantConfigs(eq(tenantId), any()))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(put("/api/v1/tenants/" + tenantId + "/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Get Public Tenant Configurations")
    class GetPublicTenantConfigsTests {

        @Test
        void getPublicTenantConfigs_Success() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.AVERAGE_MEMBERS_PER_HOUSEHOLD, new SimpleConfigValueDTO("4.5"));
            configs.put(TenantConfigKeyEnum.WATER_NORM, new SimpleConfigValueDTO("55"));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder().tenantId(tenantId).configs(configs).build();

            when(tenantManagementService.getTenantConfigs(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/config/public", tenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Public tenant configurations retrieved successfully"))
                    .andExpect(jsonPath("$.data.configs.AVERAGE_MEMBERS_PER_HOUSEHOLD.value").value("4.5"))
                    .andExpect(jsonPath("$.data.configs.WATER_NORM.value").value("55"));

            verify(tenantManagementService).getTenantConfigs(eq(tenantId), any());
        }

        @Test
        void getPublicTenantConfigs_OnlyPublicKeysPassedToService() throws Exception {
            Integer tenantId = 1;
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder().tenantId(tenantId).configs(new HashMap<>()).build();

            when(tenantManagementService.getTenantConfigs(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/config/public", tenantId))
                    .andExpect(status().isOk());

            verify(tenantManagementService).getTenantConfigs(eq(tenantId), argThat(keys -> {
                if (keys == null) return false;
                // Must contain all public keys
                boolean hasPublicKeys = keys.contains(TenantConfigKeyEnum.AVERAGE_MEMBERS_PER_HOUSEHOLD)
                        && keys.contains(TenantConfigKeyEnum.WATER_NORM);
                // Must NOT contain managed-value keys (stored value is an internal detail)
                boolean hasNoManagedKeys = !keys.contains(TenantConfigKeyEnum.TENANT_LOGO);
                // Must NOT contain sensitive keys
                boolean hasNoSensitiveKeys = !keys.contains(TenantConfigKeyEnum.MESSAGE_BROKER_CONNECTION_SETTINGS)
                        && !keys.contains(TenantConfigKeyEnum.STATE_IT_SYSTEM_CONNECTION)
                        && !keys.contains(TenantConfigKeyEnum.WHATSAPP_MESSAGE_TEMPLATES);
                return hasPublicKeys && hasNoManagedKeys && hasNoSensitiveKeys;
            }));
        }

        @Test
        void getPublicTenantConfigs_TenantNotFound_Returns404() throws Exception {
            Integer tenantId = 999;
            when(tenantManagementService.getTenantConfigs(eq(tenantId), any()))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/config/public", tenantId))
                    .andExpect(status().isNotFound());
        }

        @Test
        void getPublicTenantConfigs_InternalError_Returns500() throws Exception {
            Integer tenantId = 1;
            when(tenantManagementService.getTenantConfigs(eq(tenantId), any()))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/config/public", tenantId))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("Get Tenant Config Status")
    class GetTenantConfigStatusTests {

        @Test
        void getTenantConfigStatus_Success() throws Exception {
            Integer tenantId = 1;
            Map<TenantConfigKeyEnum, TenantConfigStatusResponseDTO.ConfigEntry> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO,
                    TenantConfigStatusResponseDTO.ConfigEntry.builder().status(ConfigStatusEnum.CONFIGURED).build());

            TenantConfigStatusResponseDTO response = TenantConfigStatusResponseDTO.builder()
                    .tenantId(tenantId)
                    .summary(TenantConfigStatusResponseDTO.Summary.builder()
                            .total(19).configured(1).pending(18).build())
                    .configs(configs)
                    .build();

            when(tenantManagementService.getTenantConfigStatus(tenantId)).thenReturn(response);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/config/status", tenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Configuration status retrieved successfully"))
                    .andExpect(jsonPath("$.data.tenantId").value(1))
                    .andExpect(jsonPath("$.data.summary.total").value(19))
                    .andExpect(jsonPath("$.data.summary.configured").value(1))
                    .andExpect(jsonPath("$.data.summary.pending").value(18))
                    .andExpect(jsonPath("$.data.configs.TENANT_LOGO.status").value("CONFIGURED"));

            verify(tenantManagementService).getTenantConfigStatus(tenantId);
        }

        @Test
        void getTenantConfigStatus_TenantNotFound() throws Exception {
            Integer tenantId = 999;
            when(tenantManagementService.getTenantConfigStatus(tenantId))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/config/status", tenantId))
                    .andExpect(status().isNotFound());
        }
    }
}
