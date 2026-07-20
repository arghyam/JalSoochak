package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.WaterSupplyThresholdConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetSystemConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.SystemConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigKeyException;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("System Management Service Tests")
class SystemManagementServiceImplTest {

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SystemManagementServiceImpl systemManagementService;

    private MockedStatic<SecurityUtils> mockedSecurityUtils;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockedSecurityUtils = mockStatic(SecurityUtils.class);
        objectMapper = new ObjectMapper();
        systemManagementService =
                new SystemManagementServiceImpl(tenantCommonRepository, objectMapper, eventPublisher);
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtils.close();
    }

    @Nested
    @DisplayName("Get System Configs Tests")
    class GetSystemConfigsTests {

        @Test
        @DisplayName("Should return all configs when no key filter provided")
        void getSystemConfigs_Success() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name())
                            .configValue("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            SystemConfigResponseDTO result = systemManagementService.getSystemConfigs(null);

            assertNotNull(result);
            ConfigValueDTO configValue = result.getConfigs().get(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD);
            assertNotNull(configValue);
            assertTrue(configValue instanceof WaterSupplyThresholdConfigDTO);
            assertEquals(20.0, ((WaterSupplyThresholdConfigDTO) configValue).getUndersupplyThresholdPercent());
            assertEquals(30.0, ((WaterSupplyThresholdConfigDTO) configValue).getOversupplyThresholdPercent());
        }

        @Test
        @DisplayName("Should return only requested keys when key filter is provided")
        void getSystemConfigs_WithKeyFilter_ReturnsOnlyRequestedKeys() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name())
                            .configValue("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}")
                            .build(),
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD.name())
                            .configValue("{\"value\": \"100\"}")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            Set<SystemConfigKeyEnum> filter = EnumSet.of(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD);
            SystemConfigResponseDTO result = systemManagementService.getSystemConfigs(filter);

            assertNotNull(result);
            assertEquals(1, result.getConfigs().size());
            assertTrue(result.getConfigs().containsKey(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD));
            assertFalse(result.getConfigs().containsKey(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD));
        }

        @Test
        @DisplayName("Should return empty map when no configs exist in DB")
        void getSystemConfigs_EmptyResult_ReturnsEmptyMap() {
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(List.of());

            SystemConfigResponseDTO result = systemManagementService.getSystemConfigs(null);

            assertNotNull(result);
            assertTrue(result.getConfigs().isEmpty());
        }

        @Test
        @DisplayName("Should throw InvalidConfigKeyException for unknown key in DB")
        void getSystemConfigs_InvalidKeyInDB_ThrowsInvalidConfigKeyException() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey("INVALID_KEY")
                            .configValue("{\"value\": \"x\"}")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            assertThrows(InvalidConfigKeyException.class, () -> systemManagementService.getSystemConfigs(null));
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException for malformed JSON in DB")
        void getSystemConfigs_MalformedValue_ThrowsInvalidConfigValueException() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name())
                            .configValue("not-valid-json{{{") // malformed JSON
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            assertThrows(InvalidConfigValueException.class, () -> systemManagementService.getSystemConfigs(null));
        }

        @Test
        @DisplayName("Should return all configs when multiple keys exist in DB")
        void getSystemConfigs_MultipleKeys_ReturnsAll() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name())
                            .configValue("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}")
                            .build(),
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD.name())
                            .configValue("{\"value\": \"100\"}")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            SystemConfigResponseDTO result = systemManagementService.getSystemConfigs(null);

            assertEquals(2, result.getConfigs().size());
            assertEquals(20.0, ((WaterSupplyThresholdConfigDTO) result.getConfigs()
                    .get(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD)).getUndersupplyThresholdPercent());
            assertEquals("100", ((SimpleConfigValueDTO) result.getConfigs()
                    .get(SystemConfigKeyEnum.LOCATION_AFFINITY_THRESHOLD)).getValue());
        }
    }

    @Nested
    @DisplayName("Set System Configs Tests")
    class SetSystemConfigsTests {

        @Test
        @DisplayName("Should upsert and return saved config")
        void setSystemConfigs_Success() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            newConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name())
                    .configValue("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}")
                    .build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("admin-uuid");
            when(tenantCommonRepository.findUserIdByUuid("admin-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.upsertConfig(eq(0),
                    eq(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name()), anyString(), eq(1)))
                    .thenReturn(Optional.of(savedConfig));

            SystemConfigResponseDTO result = systemManagementService.setSystemConfigs(request);

            assertNotNull(result);
            ConfigValueDTO configValue = result.getConfigs().get(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD);
            assertNotNull(configValue);
            assertTrue(configValue instanceof WaterSupplyThresholdConfigDTO);
            assertEquals(20.0, ((WaterSupplyThresholdConfigDTO) configValue).getUndersupplyThresholdPercent());
            assertEquals(30.0, ((WaterSupplyThresholdConfigDTO) configValue).getOversupplyThresholdPercent());
        }

        @Test
        @DisplayName("Should publish INCLUDED_WORK_STATUSES_UPDATED event for tenant-0 (national default)")
        void setSystemConfigs_IncludedWorkStatuses_PublishesNationalEvent() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            newConfigs.put(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES,
                    objectMapper.readTree("{\"workStatuses\": [4, 1, 4]}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES.name())
                    .configValue("{\"workStatuses\": [1, 4]}")
                    .build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("admin-uuid");
            when(tenantCommonRepository.findUserIdByUuid("admin-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.upsertConfig(eq(0),
                    eq(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES.name()), anyString(), eq(1)))
                    .thenReturn(Optional.of(savedConfig));

            systemManagementService.setSystemConfigs(request);

            ArgumentCaptor<IncludedWorkStatusesUpdatedEvent> captor =
                    ArgumentCaptor.forClass(IncludedWorkStatusesUpdatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            IncludedWorkStatusesUpdatedEvent event = captor.getValue();
            // tenant-0 is the national default; work statuses are validated (deduped + sorted).
            assertEquals(0, event.getTenantId());
            assertEquals(List.of(1, 4), event.getWorkStatuses());
        }

        @Test
        @DisplayName("Should reject INCLUDED_WORK_STATUSES with a value outside the allowed set")
        void setSystemConfigs_IncludedWorkStatuses_InvalidValue_Throws() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            newConfigs.put(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES,
                    objectMapper.readTree("{\"workStatuses\": [4, 9]}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES.name())
                    .configValue("{\"workStatuses\": [4, 9]}")
                    .build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("admin-uuid");
            when(tenantCommonRepository.findUserIdByUuid("admin-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.upsertConfig(eq(0),
                    eq(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES.name()), anyString(), eq(1)))
                    .thenReturn(Optional.of(savedConfig));

            assertThrows(InvalidConfigValueException.class,
                    () -> systemManagementService.setSystemConfigs(request));
        }

        @Test
        @DisplayName("Should throw RuntimeException when repository returns empty Optional")
        void setSystemConfigs_RepositoryFailure_ThrowsRuntimeException() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            newConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("admin-uuid");
            when(tenantCommonRepository.findUserIdByUuid("admin-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.upsertConfig(eq(0),
                    eq(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD.name()), anyString(), eq(1)))
                    .thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () -> systemManagementService.setSystemConfigs(request));
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when input JSON is wrong type")
        void setSystemConfigs_MalformedInput_ThrowsInvalidConfigValueException() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            // Array is not compatible with WaterSupplyThresholdConfigDTO (expects object)
            newConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("[1, 2, 3]"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("admin-uuid");
            when(tenantCommonRepository.findUserIdByUuid("admin-uuid")).thenReturn(Optional.of(1));

            assertThrows(InvalidConfigValueException.class, () -> systemManagementService.setSystemConfigs(request));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user UUID is not found in the database")
        void setSystemConfigs_UserNotFound_ThrowsResourceNotFoundException() throws Exception {
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            newConfigs.put(SystemConfigKeyEnum.WATER_QUANTITY_SUPPLY_THRESHOLD,
                    objectMapper.readTree("{\"undersupplyThresholdPercent\": 20.0, \"oversupplyThresholdPercent\": 30.0}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("unknown-uuid");
            when(tenantCommonRepository.findUserIdByUuid("unknown-uuid")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class, () -> systemManagementService.setSystemConfigs(request));
        }
    }

    @Nested
    @DisplayName("Get System Supported Channels Tests")
    class GetSystemSupportedChannelsTests {

        @Test
        @DisplayName("Should return configured channel list")
        void getSystemSupportedChannels_ReturnsChannels() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name())
                            .configValue("{\"channels\":[\"BFM\",\"ELM\",\"PDU\"]}")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            List<String> result = systemManagementService.getSystemSupportedChannels();

            assertNotNull(result);
            assertEquals(3, result.size());
            assertTrue(result.containsAll(List.of("BFM", "ELM", "PDU")));
        }

        @Test
        @DisplayName("Should return empty list when SYSTEM_SUPPORTED_CHANNELS not configured")
        void getSystemSupportedChannels_NotConfigured_ReturnsEmptyList() {
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(List.of());

            List<String> result = systemManagementService.getSystemSupportedChannels();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when config value is malformed JSON")
        void getSystemSupportedChannels_MalformedJson_ThrowsInvalidConfigValueException() {
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name())
                            .configValue("not-valid-json{{{")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            assertThrows(InvalidConfigValueException.class,
                    () -> systemManagementService.getSystemSupportedChannels());
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when channels field is null in stored config")
        void getSystemSupportedChannels_NullChannelsField_ThrowsInvalidConfigValueException() {
            // Stored JSON parses fine but has no channels field → getChannels() == null
            List<ConfigDTO> configs = List.of(
                    ConfigDTO.builder()
                            .configKey(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name())
                            .configValue("{}")
                            .build());
            when(tenantCommonRepository.findConfigsByTenantId(0)).thenReturn(configs);

            assertThrows(InvalidConfigValueException.class,
                    () -> systemManagementService.getSystemSupportedChannels());
        }
    }

    @Nested
    @DisplayName("Set System Configs — Channel transient fields Tests")
    class SetSystemConfigsChannelTests {

        @Test
        @DisplayName("Should strip degraded and removedChannels before persisting SYSTEM_SUPPORTED_CHANNELS")
        void setSystemConfigs_ChannelListConfig_TransientFieldsStripped() throws Exception {
            // Arrange: client sends degraded + removedChannels — both must be absent from the persisted value
            Map<SystemConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            newConfigs.put(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS,
                    objectMapper.readTree("{\"channels\":[\"BFM\",\"ELM\"],\"degraded\":true,\"removedChannels\":[\"PDU\"]}"));
            SetSystemConfigRequestDTO request = SetSystemConfigRequestDTO.builder().configs(newConfigs).build();

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name())
                    .configValue("{\"channels\":[\"BFM\",\"ELM\"]}")
                    .build();

            when(SecurityUtils.getCurrentUserUuid()).thenReturn("admin-uuid");
            when(tenantCommonRepository.findUserIdByUuid("admin-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.upsertConfig(eq(0),
                    eq(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name()), anyString(), eq(1)))
                    .thenReturn(Optional.of(savedConfig));

            systemManagementService.setSystemConfigs(request);

            // Assert: the string passed to upsertConfig must not contain degraded or removedChannels
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(tenantCommonRepository).upsertConfig(eq(0),
                    eq(SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name()), captor.capture(), eq(1));
            String persisted = captor.getValue();
            assertFalse(persisted.contains("degraded"), "degraded must not be persisted");
            assertFalse(persisted.contains("removedChannels"), "removedChannels must not be persisted");
        }
    }
}
