package org.arghyam.jalsoochak.tenant.controller.system;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WaterSupplyThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetSystemConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.SystemConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigKeyException;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
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

@WebMvcTest(SystemConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("System Config Controller Tests")
class SystemConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SystemManagementService systemManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Get System Configs")
    class GetSystemConfigsTests {

        @Test
        @DisplayName("Should return all system configs successfully")
        void getSystemConfigs_Success() throws Exception {
            Map<SystemConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD, new WaterSupplyThresholdConfigDTO(20.0, 30.0));
            SystemConfigResponseDTO response = SystemConfigResponseDTO.builder().configs(configs).build();

            when(systemManagementService.getSystemConfigs(any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("System configurations retrieved successfully"))
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.undersupplyThresholdPercent").value(20.0))
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.oversupplyThresholdPercent").value(30.0));
        }

        @Test
        @DisplayName("Should return empty map when no configs exist")
        void getSystemConfigs_EmptyConfigs() throws Exception {
            SystemConfigResponseDTO response = SystemConfigResponseDTO.builder()
                    .configs(Collections.emptyMap())
                    .build();

            when(systemManagementService.getSystemConfigs(any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/system/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configs").isMap());
        }

        @Test
        @DisplayName("Should filter configs by key when keys param provided")
        void getSystemConfigs_WithKeyFilter() throws Exception {
            Map<SystemConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD, new WaterSupplyThresholdConfigDTO(20.0, 30.0));
            SystemConfigResponseDTO response = SystemConfigResponseDTO.builder().configs(configs).build();

            when(systemManagementService.getSystemConfigs(any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/system/config")
                    .param("keys", "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.undersupplyThresholdPercent").value(20.0));

            verify(systemManagementService).getSystemConfigs(any());
        }

        @Test
        @DisplayName("Should return 400 when invalid key name is provided")
        void getSystemConfigs_InvalidKey_ReturnsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/system/config")
                    .param("keys", "NOT_A_VALID_SYSTEM_KEY"))
                    .andExpect(status().isBadRequest());

            verify(systemManagementService, never()).getSystemConfigs(any());
        }

        @Test
        @DisplayName("Should return 400 when service throws InvalidConfigKeyException")
        void getSystemConfigs_InvalidConfigKey_ReturnsBadRequest() throws Exception {
            when(systemManagementService.getSystemConfigs(any()))
                    .thenThrow(new InvalidConfigKeyException("Invalid system config key: STALE_KEY"));

            mockMvc.perform(get("/api/v1/system/config"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return multiple configs in response")
        void getSystemConfigs_MultipleConfigs() throws Exception {
            Map<SystemConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD, new WaterSupplyThresholdConfigDTO(20.0, 30.0));
            configs.put(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD, new SimpleConfigValueDTO("100"));
            SystemConfigResponseDTO response = SystemConfigResponseDTO.builder().configs(configs).build();

            when(systemManagementService.getSystemConfigs(any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/system/config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.undersupplyThresholdPercent").value(20.0))
                    .andExpect(jsonPath("$.data.configs.LOCATION_AFFINITY_THRESHOLD.value").value("100"));
        }
    }

    @Nested
    @DisplayName("Set System Configs")
    class SetSystemConfigsTests {

        @Test
        @DisplayName("Should set system configs successfully")
        void setSystemConfigs_Success() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> requestConfigs = new HashMap<>();
            requestConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(requestConfigs).build();

            Map<SystemConfigKeyEnum, ConfigValueDTO> responseConfigs = new HashMap<>();
            responseConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD, new WaterSupplyThresholdConfigDTO(20.0, 30.0));
            SystemConfigResponseDTO response = SystemConfigResponseDTO.builder().configs(responseConfigs).build();

            when(systemManagementService.setSystemConfigs(any(SetSystemConfigRequestDTO.class))).thenReturn(response);

            mockMvc.perform(put("/api/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("System configurations set successfully"))
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.undersupplyThresholdPercent").value(20.0))
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.oversupplyThresholdPercent").value(30.0));
        }

        @Test
        @DisplayName("Should set multiple system configs successfully")
        void setSystemConfigs_MultipleKeys() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> requestConfigs = new HashMap<>();
            requestConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));
            requestConfigs.put(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD,
                    objectMapper.readTree("{\"value\":\"100\"}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(requestConfigs).build();

            Map<SystemConfigKeyEnum, ConfigValueDTO> responseConfigs = new HashMap<>();
            responseConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD, new WaterSupplyThresholdConfigDTO(20.0, 30.0));
            responseConfigs.put(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD, new SimpleConfigValueDTO("100"));
            SystemConfigResponseDTO response = SystemConfigResponseDTO.builder().configs(responseConfigs).build();

            when(systemManagementService.setSystemConfigs(any())).thenReturn(response);

            mockMvc.perform(put("/api/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configs.WATER_QUANTITY_SUPPLY_THRESHOLD.undersupplyThresholdPercent").value(20.0))
                    .andExpect(jsonPath("$.data.configs.LOCATION_AFFINITY_THRESHOLD.value").value("100"));
        }

        @Test
        @DisplayName("Should return 400 when service throws InvalidConfigValueException")
        void setSystemConfigs_InvalidValue_ReturnsBadRequest() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> requestConfigs = new HashMap<>();
            requestConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\":200.0,\"oversupplyThresholdPercent\":200.0}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(requestConfigs).build();

            when(systemManagementService.setSystemConfigs(any()))
                    .thenThrow(new InvalidConfigValueException("Invalid value for WATER_QUANTITY_SUPPLY_THRESHOLD"));

            mockMvc.perform(put("/api/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 500 when upsert fails")
        void setSystemConfigs_RepositoryFailure_ReturnsInternalError() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> requestConfigs = new HashMap<>();
            requestConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(requestConfigs).build();

            when(systemManagementService.setSystemConfigs(any()))
                    .thenThrow(new RuntimeException("Failed to upsert system config"));

            mockMvc.perform(put("/api/v1/system/config")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError());
        }
    }
}
