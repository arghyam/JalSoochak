package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.arghyam.jalsoochak.tenant.config.TenantDefaultsProperties;
import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LogoSource;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantLogoResult;
import org.arghyam.jalsoochak.tenant.dto.internal.LanguageConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelNameDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ReasonItemDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.ConfigStatusEnum;
import org.arghyam.jalsoochak.tenant.enums.RegionTypeEnum;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.arghyam.jalsoochak.tenant.enums.StatusEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.event.TenantCreatedEvent;
import org.arghyam.jalsoochak.tenant.event.TenantDeactivatedEvent;
import org.arghyam.jalsoochak.tenant.event.TenantUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigKeyException;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.LocationHierarchyStructureLockedException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.exception.StorageException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantSchemaRepository;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.arghyam.jalsoochak.tenant.storage.ObjectStorageService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Comprehensive unit tests for TenantManagementServiceImpl.
 * Covers success paths, error scenarios, edge cases, and integration with dependencies.
 * Follows AAA (Arrange-Act-Assert) pattern and includes detailed test method names.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tenant Management Service - Comprehensive Tests")
class TenantManagementServiceImplTest {

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private TenantSchemaRepository tenantSchemaRepository;

    @Mock
    private TenantDefaultsProperties tenantDefaults;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TenantSchedulerManager schedulerManager;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private SystemManagementService systemManagementService;

    private ObjectMapper objectMapper;

    private TenantManagementServiceImpl tenantManagementService;

    private MockedStatic<SecurityUtils> mockedSecurityUtils;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockedSecurityUtils = mockStatic(SecurityUtils.class);
        lenient().when(tenantDefaults.getLgdLocationHierarchy()).thenReturn(Collections.emptyList());
        lenient().when(tenantDefaults.getDeptLocationHierarchy()).thenReturn(Collections.emptyList());
        lenient().when(tenantDefaults.getMeterChangeReasons()).thenReturn(Collections.emptyList());
        lenient().when(tenantDefaults.getSupplyOutageReasons()).thenReturn(Collections.emptyList());
        
        // Manually create service with real ObjectMapper and mocked dependencies
        tenantManagementService = new TenantManagementServiceImpl(
            tenantCommonRepository,
            tenantSchemaRepository,
            objectMapper,
            tenantDefaults,
            eventPublisher,
            schedulerManager,
            objectStorageService,
            systemManagementService
        );
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtils.close();
    }

    @Nested
    @DisplayName("Create Tenant Tests")
    class CreateTenantTests {

        @Test
        @DisplayName("Should create tenant successfully with all valid data")
        void testCreateTenant_Success() throws Exception {
            // Arrange
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .name("Test Tenant")
                    .stateCode("TT")
                    .lgdCode(123)
                    .build();

            TenantResponseDTO expectedResponse = TenantResponseDTO.builder()
                    .id(1)
                    .name("Test Tenant")
                    .stateCode("TT")
                    .status(TenantStatusEnum.ONBOARDED.name())
                    .build();

            when(tenantCommonRepository.findByStateCode("TT")).thenReturn(Optional.empty());
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.createTenant(any(CreateTenantRequestDTO.class), anyInt()))
                    .thenReturn(Optional.of(expectedResponse));
            when(tenantCommonRepository.upsertConfig(eq(1), eq("METER_CHANGE_REASONS"), anyString(), eq(100)))
                    .thenReturn(Optional.of(ConfigDTO.builder().build()));
            when(tenantCommonRepository.upsertConfig(eq(1), eq("SUPPLY_OUTAGE_REASONS"), anyString(), eq(100)))
                    .thenReturn(Optional.of(ConfigDTO.builder().build()));
            when(tenantCommonRepository.upsertConfig(eq(1), eq("LOCATION_CHECK_REQUIRED"), anyString(), eq(100)))
                    .thenReturn(Optional.of(ConfigDTO.builder().build()));

            // Act
            TenantResponseDTO result = tenantManagementService.createTenant(request);

            // Assert
            assertNotNull(result);
            assertEquals("Test Tenant", result.getName());
            assertEquals("TT", result.getStateCode());
            assertEquals(TenantStatusEnum.ONBOARDED.name(), result.getStatus());

            verify(tenantCommonRepository).findByStateCode("TT");
            verify(tenantCommonRepository).createTenant(eq(request), eq(100));
            verify(tenantCommonRepository).provisionTenantSchema("tenant_tt");
            verify(eventPublisher).publishEvent(any(TenantCreatedEvent.class));
        }

        @Test
        @DisplayName("Should throw exception when tenant with same state code already exists")
        void testCreateTenant_DuplicateStateCode() {
            // Arrange
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .stateCode("TT")
                    .build();

            TenantResponseDTO existing = TenantResponseDTO.builder().build();
            when(tenantCommonRepository.findByStateCode("TT"))
                    .thenReturn(Optional.of(existing));

            // Act & Assert
            assertThrows(IllegalStateException.class, 
                    () -> tenantManagementService.createTenant(request));
            
            verify(tenantCommonRepository).findByStateCode("TT");
            verify(tenantCommonRepository, never()).createTenant(any(), any());
        }

        @Test
        @DisplayName("Should handle tenant creation failure gracefully")
        void testCreateTenant_CreationFailed() {
            // Arrange
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .stateCode("TT")
                    .build();

            when(tenantCommonRepository.findByStateCode("TT")).thenReturn(Optional.empty());
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.createTenant(any(), any()))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(RuntimeException.class,
                    () -> tenantManagementService.createTenant(request));

            verify(tenantCommonRepository).createTenant(any(), any());
        }

        @Test
        @DisplayName("Should seed default configs for new tenant after creation")
        void testCreateTenant_SeedsDefaultConfigs() throws Exception {
            // Arrange
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .name("Test Tenant")
                    .stateCode("TT")
                    .lgdCode(123)
                    .build();

            TenantResponseDTO tenant = TenantResponseDTO.builder()
                    .id(1)
                    .name("Test Tenant")
                    .stateCode("TT")
                    .status(TenantStatusEnum.ACTIVE.name())
                    .build();

            List<LocationLevelConfigDTO> lgdLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );
            List<LocationLevelConfigDTO> deptLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Zone").build())).build()
            );
            List<ReasonItemDTO> reasons = List.of(
                    ReasonItemDTO.builder().id("METER_REPLACED").name("Meter Replaced")
                            .sequenceOrder(1).isDefault(true).editable(true).build()
            );
            List<ReasonItemDTO> supplyOutageReasons = List.of(
                    ReasonItemDTO.builder().id("PUMP_FAILURE").name("Pump Failure")
                            .sequenceOrder(1).isDefault(true).editable(true).build()
            );

            when(tenantCommonRepository.findByStateCode("TT")).thenReturn(Optional.empty());
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.createTenant(any(CreateTenantRequestDTO.class), anyInt()))
                    .thenReturn(Optional.of(tenant));
            when(tenantDefaults.getLgdLocationHierarchy()).thenReturn(lgdLevels);
            when(tenantDefaults.getDeptLocationHierarchy()).thenReturn(deptLevels);
            when(tenantDefaults.getMeterChangeReasons()).thenReturn(reasons);
            when(tenantDefaults.getSupplyOutageReasons()).thenReturn(supplyOutageReasons);
            when(tenantCommonRepository.upsertConfig(eq(1), eq("METER_CHANGE_REASONS"), anyString(), eq(100)))
                    .thenReturn(Optional.of(ConfigDTO.builder().build()));
            when(tenantCommonRepository.upsertConfig(eq(1), eq("SUPPLY_OUTAGE_REASONS"), anyString(), eq(100)))
                    .thenReturn(Optional.of(ConfigDTO.builder().build()));
            when(tenantCommonRepository.upsertConfig(eq(1), eq("LOCATION_CHECK_REQUIRED"), anyString(), eq(100)))
                    .thenReturn(Optional.of(ConfigDTO.builder().build()));

            // Act
            tenantManagementService.createTenant(request);

            // Assert — both hierarchy types seeded
            verify(tenantSchemaRepository).setLocationHierarchy("tenant_tt", RegionTypeEnum.LGD, lgdLevels, 100);
            verify(tenantSchemaRepository).setLocationHierarchy("tenant_tt", RegionTypeEnum.DEPARTMENT, deptLevels, 100);
            // All three reason/flag configs written via upsertConfig
            verify(tenantCommonRepository).upsertConfig(eq(1), eq("METER_CHANGE_REASONS"), anyString(), eq(100));
            verify(tenantCommonRepository).upsertConfig(eq(1), eq("SUPPLY_OUTAGE_REASONS"), anyString(), eq(100));
            verify(tenantCommonRepository).upsertConfig(eq(1), eq("LOCATION_CHECK_REQUIRED"), anyString(), eq(100));
        }
    }

    @Nested
    @DisplayName("Update Tenant Tests")
    class UpdateTenantTests {

        @Test
        @DisplayName("Should update tenant successfully")
        void testUpdateTenant_Success() throws Exception {
            // Arrange
            Integer tenantId = 1;
            UpdateTenantRequestDTO request = new UpdateTenantRequestDTO();
            request.setStatus(TenantStatusEnum.ACTIVE.name());

            TenantResponseDTO existing = TenantResponseDTO.builder()
                    .id(tenantId).stateCode("TT").name("Tenant").status(TenantStatusEnum.ACTIVE.name()).build();
            TenantResponseDTO updated = TenantResponseDTO.builder()
                    .id(tenantId).stateCode("TT").name("Tenant").status(TenantStatusEnum.ACTIVE.name()).build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(existing));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.updateTenant(anyInt(), any(UpdateTenantRequestDTO.class), anyInt()))
                    .thenReturn(Optional.of(updated));

            // Act
            TenantResponseDTO result = tenantManagementService.updateTenant(tenantId, request);

            // Assert
            assertNotNull(result);
            assertEquals(TenantStatusEnum.ACTIVE.name(), result.getStatus());
            verify(tenantCommonRepository).updateTenant(anyInt(), any(UpdateTenantRequestDTO.class), anyInt());
            verify(eventPublisher).publishEvent(any(TenantUpdatedEvent.class));
        }

        @Test
        @DisplayName("Should throw exception when trying to deactivate via update endpoint")
        void testUpdateTenant_CannotDeactivate() {
            // Arrange
            Integer tenantId = 1;
            UpdateTenantRequestDTO request = new UpdateTenantRequestDTO();
            request.setStatus(TenantStatusEnum.INACTIVE.name());

            TenantResponseDTO existing = TenantResponseDTO.builder().build();
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(existing));

            // Act & Assert
            assertThrows(IllegalArgumentException.class, 
                    () -> tenantManagementService.updateTenant(tenantId, request));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found")
        void testUpdateTenant_NotFound() {
            // Arrange
            Integer tenantId = 999;
            UpdateTenantRequestDTO request = UpdateTenantRequestDTO.builder().status(TenantStatusEnum.ACTIVE.name()).build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.updateTenant(tenantId, request));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when update returns empty optional")
        void testUpdateTenant_UpdateReturnsEmpty() {
            // Arrange
            Integer tenantId = 1;
            UpdateTenantRequestDTO request = UpdateTenantRequestDTO.builder().status(TenantStatusEnum.ACTIVE.name()).build();
            TenantResponseDTO existing = TenantResponseDTO.builder().id(tenantId).build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(existing));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.updateTenant(anyInt(), any(), anyInt())).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.updateTenant(tenantId, request));
        }
    }

    @Nested
    @DisplayName("Deactivate Tenant Tests")
    class DeactivateTenantTests {

        @Test
        @DisplayName("Should deactivate tenant successfully")
        void testDeactivateTenant_Success() throws Exception {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO existing = TenantResponseDTO.builder()
                    .id(tenantId).stateCode("TT").name("Tenant").status(TenantStatusEnum.ACTIVE.name()).build();
            TenantResponseDTO deactivated = TenantResponseDTO.builder()
                    .id(tenantId).stateCode("TT").name("Tenant").status(TenantStatusEnum.INACTIVE.name()).build();

            when(tenantCommonRepository.findById(tenantId))
                    .thenReturn(Optional.of(existing))
                    .thenReturn(Optional.of(deactivated));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));

            // Act
            tenantManagementService.deactivateTenant(tenantId);

            // Assert
            verify(tenantCommonRepository).deactivateTenant(anyInt(), anyInt());
            verify(eventPublisher).publishEvent(any(TenantDeactivatedEvent.class));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found")
        void testDeactivateTenant_NotFound() {
            // Arrange
            Integer tenantId = 999;
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class, 
                    () -> tenantManagementService.deactivateTenant(tenantId));
        }
    }

    @Nested
    @DisplayName("Get All Tenants Tests")
    class GetAllTenantsTests {

        @Test
        @DisplayName("Should retrieve all tenants with pagination")
        void testGetAllTenants_Success() {
            // Arrange
            int page = 0;
            int size = 10;
            List<TenantResponseDTO> tenants = List.of(
                    TenantResponseDTO.builder().id(1).name("Tenant1").build(),
                    TenantResponseDTO.builder().id(2).name("Tenant2").build()
            );

            when(tenantCommonRepository.findAll(size, 0, null, null)).thenReturn(tenants);
            when(tenantCommonRepository.countAllTenants(null, null)).thenReturn(2L);

            // Act
            PageResponseDTO<TenantResponseDTO> result = tenantManagementService.getAllTenants(page, size, null, null);

            // Assert
            assertNotNull(result);
            assertEquals(2, result.getContent().size());
            assertEquals(2L, result.getTotalElements());
            verify(tenantCommonRepository).findAll(size, 0, null, null);
            verify(tenantCommonRepository).countAllTenants(null, null);
        }

        @Test
        @DisplayName("Should return empty list when no tenants exist")
        void testGetAllTenants_Empty() {
            // Arrange
            when(tenantCommonRepository.findAll(10, 0, null, null)).thenReturn(Collections.emptyList());
            when(tenantCommonRepository.countAllTenants(null, null)).thenReturn(0L);

            // Act
            PageResponseDTO<TenantResponseDTO> result = tenantManagementService.getAllTenants(0, 10, null, null);

            // Assert
            assertNotNull(result);
            assertEquals(0, result.getContent().size());
            assertEquals(0L, result.getTotalElements());
        }

        @Test
        @DisplayName("Should filter tenants by status")
        void testGetAllTenants_FilterByStatus() {
            int page = 0, size = 10;
            List<TenantResponseDTO> tenants = List.of(
                    TenantResponseDTO.builder().id(1).name("Active Tenant").status(TenantStatusEnum.ACTIVE.name()).build()
            );

            when(tenantCommonRepository.findAll(size, 0, TenantStatusEnum.ACTIVE, null)).thenReturn(tenants);
            when(tenantCommonRepository.countAllTenants(TenantStatusEnum.ACTIVE, null)).thenReturn(1L);

            PageResponseDTO<TenantResponseDTO> result = tenantManagementService.getAllTenants(page, size, TenantStatusEnum.ACTIVE, null);

            assertNotNull(result);
            assertEquals(1, result.getContent().size());
            assertEquals(TenantStatusEnum.ACTIVE.name(), result.getContent().get(0).getStatus());
            verify(tenantCommonRepository).findAll(size, 0, TenantStatusEnum.ACTIVE, null);
            verify(tenantCommonRepository).countAllTenants(TenantStatusEnum.ACTIVE, null);
        }

        @Test
        @DisplayName("Should filter tenants by search term")
        void testGetAllTenants_FilterBySearch() {
            int page = 0, size = 10;
            List<TenantResponseDTO> tenants = List.of(
                    TenantResponseDTO.builder().id(1).name("Madhya Pradesh").build()
            );

            when(tenantCommonRepository.findAll(size, 0, null, "madhya")).thenReturn(tenants);
            when(tenantCommonRepository.countAllTenants(null, "madhya")).thenReturn(1L);

            PageResponseDTO<TenantResponseDTO> result = tenantManagementService.getAllTenants(page, size, null, "madhya");

            assertNotNull(result);
            assertEquals(1, result.getContent().size());
            assertEquals("Madhya Pradesh", result.getContent().get(0).getName());
            verify(tenantCommonRepository).findAll(size, 0, null, "madhya");
            verify(tenantCommonRepository).countAllTenants(null, "madhya");
        }

        @Test
        @DisplayName("Should filter tenants by status and search term combined")
        void testGetAllTenants_FilterByStatusAndSearch() {
            int page = 0, size = 10;
            List<TenantResponseDTO> tenants = List.of(
                    TenantResponseDTO.builder().id(1).name("Madhya Pradesh").status(TenantStatusEnum.ACTIVE.name()).build()
            );

            when(tenantCommonRepository.findAll(size, 0, TenantStatusEnum.ACTIVE, "madhya")).thenReturn(tenants);
            when(tenantCommonRepository.countAllTenants(TenantStatusEnum.ACTIVE, "madhya")).thenReturn(1L);

            PageResponseDTO<TenantResponseDTO> result = tenantManagementService.getAllTenants(page, size, TenantStatusEnum.ACTIVE, "madhya");

            assertNotNull(result);
            assertEquals(1, result.getContent().size());
            verify(tenantCommonRepository).findAll(size, 0, TenantStatusEnum.ACTIVE, "madhya");
            verify(tenantCommonRepository).countAllTenants(TenantStatusEnum.ACTIVE, "madhya");
        }
    }

    @Nested
    @DisplayName("Get Tenant Configurations Tests")
    class GetTenantConfigsTests {

        @Test
        @DisplayName("Should retrieve all tenant configurations without key filter")
        void testGetTenantConfigs_AllConfigs() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();
            List<ConfigDTO> configsList = Arrays.asList(
                    ConfigDTO.builder()
                            .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                            .configValue("{\"value\":\"https://brand.com/logo.png\"}")
                            .build()
            );

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(configsList);

            // Act
            TenantConfigResponseDTO result = tenantManagementService.getTenantConfigs(tenantId, null);

            // Assert
            assertNotNull(result);
            assertEquals(tenantId, result.getTenantId());
            assertTrue(result.getConfigs().containsKey(TenantConfigKeyEnum.TENANT_LOGO));
            ConfigValueDTO configValue = result.getConfigs().get(TenantConfigKeyEnum.TENANT_LOGO);
            assertTrue(configValue instanceof SimpleConfigValueDTO);
            assertEquals("https://brand.com/logo.png", ((SimpleConfigValueDTO) configValue).getValue());
            verify(tenantCommonRepository).findById(tenantId);
        }

        @Test
        @DisplayName("Should handle null filter keys and retrieve all configurations")
        void testGetTenantConfigs_NullFilterKeys() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();
            List<ConfigDTO> configsList = Collections.singletonList(
                    ConfigDTO.builder()
                            .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                            .configValue("{\"value\": \"https://brand.com/logo.png\"}")
                            .build()
            );

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId))
                    .thenReturn(configsList);

            // Act
            TenantConfigResponseDTO result = tenantManagementService.getTenantConfigs(tenantId, null);

            // Assert
            assertNotNull(result);
            assertEquals(1, result.getConfigs().size());
            assertTrue(result.getConfigs().containsKey(TenantConfigKeyEnum.TENANT_LOGO));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found")
        void testGetTenantConfigs_TenantNotFound() {
            // Arrange
            Integer tenantId = 999;
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class, 
                    () -> tenantManagementService.getTenantConfigs(tenantId, null));
        }

        @Test
        @DisplayName("Should return empty configurations when no configs exist")
        void testGetTenantConfigs_EmptyConfigs() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(Collections.emptyList());

            // Act
            TenantConfigResponseDTO result = tenantManagementService.getTenantConfigs(tenantId, null);

            // Assert
            assertNotNull(result);
            assertEquals(tenantId, result.getTenantId());
            assertTrue(result.getConfigs().isEmpty());
        }

        @Test
        @DisplayName("Should return degraded=true with removedChannels when tenant has channels no longer in system")
        void testGetTenantConfigs_ChannelIntersection_DegradedWhenOrphanedChannels() {
            // Arrange: tenant was configured with [BFM, ELM, PDU, IOT, MAN], system reduced to [BFM, ELM, PDU]
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            List<ConfigDTO> tenantConfigs = List.of(
                    ConfigDTO.builder()
                            .configKey(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name())
                            .configValue("{\"channels\":[\"BFM\",\"ELM\",\"PDU\",\"IOT\",\"MAN\"]}")
                            .build());

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(tenantConfigs);
            when(systemManagementService.getSystemSupportedChannels()).thenReturn(List.of("BFM", "ELM", "PDU"));

            // Act
            TenantConfigResponseDTO result = tenantManagementService.getTenantConfigs(tenantId, null);

            // Assert
            org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO channels =
                    (org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO)
                            result.getConfigs().get(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS);
            assertNotNull(channels);
            assertEquals(3, channels.getChannels().size());
            assertTrue(channels.getChannels().containsAll(List.of("BFM", "ELM", "PDU")));
            assertEquals(Boolean.TRUE, channels.getDegraded());
            assertNotNull(channels.getRemovedChannels());
            assertEquals(2, channels.getRemovedChannels().size());
            assertTrue(channels.getRemovedChannels().containsAll(List.of("IOT", "MAN")));
        }

        @Test
        @DisplayName("Should return degraded=null with no removedChannels when all tenant channels still in system")
        void testGetTenantConfigs_ChannelIntersection_NotDegradedWhenAllChannelsValid() {
            // Arrange: tenant channels are a valid subset of system channels
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            List<ConfigDTO> tenantConfigs = List.of(
                    ConfigDTO.builder()
                            .configKey(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name())
                            .configValue("{\"channels\":[\"BFM\",\"ELM\"]}")
                            .build());

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(tenantConfigs);
            when(systemManagementService.getSystemSupportedChannels()).thenReturn(List.of("BFM", "ELM", "PDU", "IOT", "MAN"));

            // Act
            TenantConfigResponseDTO result = tenantManagementService.getTenantConfigs(tenantId, null);

            // Assert
            org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO channels =
                    (org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO)
                            result.getConfigs().get(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS);
            assertNotNull(channels);
            assertEquals(2, channels.getChannels().size());
            assertTrue(channels.getChannels().containsAll(List.of("BFM", "ELM")));
            assertNull(channels.getDegraded());
            assertNull(channels.getRemovedChannels());
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when stored TENANT_SUPPORTED_CHANNELS has null channels field")
        void getTenantConfigs_NullChannelsInStoredConfig_ThrowsInvalidConfigValueException() {
            // Arrange: stored config JSON parses successfully but channels field is absent (null)
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            List<ConfigDTO> tenantConfigs = List.of(
                    ConfigDTO.builder()
                            .configKey(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name())
                            .configValue("{}")  // valid JSON but missing channels field → getChannels() == null
                            .build());

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(tenantConfigs);

            assertThrows(InvalidConfigValueException.class,
                    () -> tenantManagementService.getTenantConfigs(tenantId, null));
        }
    }

    @Nested
    @DisplayName("Set Tenant Configurations Tests")
    class SetTenantConfigsTests {

        @Test
        @DisplayName("Should set tenant configurations successfully")
        void testSetTenantConfigs_Success() throws Exception {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN")
                    .status(TenantStatusEnum.ACTIVE.name()).build();
            Map<TenantConfigKeyEnum, JsonNode> newConfigs = new HashMap<>();
            // Input should be JSON that matches SimpleConfigValueDTO structure with "value" field
            newConfigs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON, objectMapper.readTree("{\"value\": \"{\\\"welcome\\\": \\\"...\\\"}\"}"));

            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder()
                    .configs(newConfigs)
                    .build();

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name())
                    .configValue("{\"value\":\"{\\\"welcome\\\": \\\"...\\\"}\"}") 
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(
                    eq(tenantId), 
                    eq(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name()),
                    anyString(),
                    eq(100)
            )).thenReturn(Optional.of(savedConfig));

            // Act
            TenantConfigResponseDTO result = tenantManagementService.setTenantConfigs(tenantId, request);

            // Assert
            assertNotNull(result);
            assertEquals(tenantId, result.getTenantId());
            ConfigValueDTO configValue = result.getConfigs().get(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON);
            assertTrue(configValue instanceof SimpleConfigValueDTO);
            assertEquals("{\"welcome\": \"...\"}", ((SimpleConfigValueDTO) configValue).getValue());
            verify(tenantCommonRepository).upsertConfig(eq(tenantId), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw exception when config upsert fails")
        void testSetTenantConfigs_UpsertFailed() throws Exception {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).build();
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            // Use valid JSON format that matches SimpleConfigValueDTO structure
            configs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON, objectMapper.readTree("{\"value\": \"test value\"}"));

            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder()
                    .configs(configs)
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(tenantId), anyString(), anyString(), eq(100)))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(RuntimeException.class, 
                    () -> tenantManagementService.setTenantConfigs(tenantId, request));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found")
        void testSetTenantConfigs_TenantNotFound() {
            // Arrange
            Integer tenantId = 999;
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.setTenantConfigs(tenantId, request));
        }

        @Test
        @DisplayName("Should call rescheduleForTenant after persisting a GENERIC schedule config")
        void testSetTenantConfigs_TriggersReschedule() throws Exception {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder()
                    .id(tenantId).stateCode("MP").status(TenantStatusEnum.ACTIVE.name()).build();
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.PUMP_OPERATOR_REMINDER_NUDGE_TIME,
                    objectMapper.readTree("{\"nudge\":{\"schedule\":{\"hour\":8,\"minute\":0}}}"));

            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder()
                    .configs(configs)
                    .build();

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.PUMP_OPERATOR_REMINDER_NUDGE_TIME.name())
                    .configValue("{\"nudge\":{\"schedule\":{\"hour\":8,\"minute\":0}}}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(
                    eq(tenantId),
                    eq(TenantConfigKeyEnum.PUMP_OPERATOR_REMINDER_NUDGE_TIME.name()),
                    anyString(),
                    eq(100)))
                    .thenReturn(Optional.of(savedConfig));

            // Act
            tenantManagementService.setTenantConfigs(tenantId, request);

            // Assert
            verify(schedulerManager).rescheduleForTenant(tenantId, "MP");
        }

        @Test
        @DisplayName("Should throw InvalidConfigKeyException when a managed-value key is included")
        void setTenantConfigs_managedValueKey_throwsInvalidConfigKeyException() throws Exception {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, objectMapper.readTree("{\"value\":\"https://example.com/logo.png\"}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));

            assertThrows(InvalidConfigKeyException.class,
                    () -> tenantManagementService.setTenantConfigs(tenantId, request));
            verify(tenantCommonRepository, never()).upsertConfig(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should save tenant channels when all are valid system channels")
        void setTenantConfigs_ValidChannels_SavesSuccessfully() throws Exception {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN")
                    .status(TenantStatusEnum.ACTIVE.name()).build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS,
                    objectMapper.readTree("{\"channels\":[\"BFM\",\"ELM\"]}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name())
                    .configValue("{\"channels\":[\"BFM\",\"ELM\"]}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(systemManagementService.getSystemSupportedChannels()).thenReturn(List.of("BFM", "ELM", "PDU", "IOT", "MAN"));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            // Act
            TenantConfigResponseDTO result = tenantManagementService.setTenantConfigs(tenantId, request);

            // Assert
            assertNotNull(result);
            org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO channels =
                    (org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO)
                            result.getConfigs().get(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS);
            assertNotNull(channels);
            assertTrue(channels.getChannels().containsAll(List.of("BFM", "ELM")));
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when tenant channel is not in system channels")
        void setTenantConfigs_ChannelNotInSystemChannels_ThrowsException() throws Exception {
            // Arrange: system has [BFM, ELM, PDU], tenant tries to set [BFM, IOT] — IOT not in system
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS,
                    objectMapper.readTree("{\"channels\":[\"BFM\",\"IOT\"]}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(systemManagementService.getSystemSupportedChannels()).thenReturn(List.of("BFM", "ELM", "PDU"));

            // Act & Assert
            assertThrows(InvalidConfigValueException.class,
                    () -> tenantManagementService.setTenantConfigs(tenantId, request));
            verify(tenantCommonRepository, never()).upsertConfig(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException  when system channels are empty")
        void setTenantConfigs_SystemChannelsNotConfigured_ThrowsException() throws Exception {
            // Arrange: SYSTEM_SUPPORTED_CHANNELS not set yet
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS,
                    objectMapper.readTree("{\"channels\":[\"BFM\"]}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(systemManagementService.getSystemSupportedChannels()).thenReturn(Collections.emptyList());

            // Act & Assert
            assertThrows(InvalidConfigValueException.class,
                    () -> tenantManagementService.setTenantConfigs(tenantId, request));
            verify(tenantCommonRepository, never()).upsertConfig(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should not persist degraded or removedChannels fields when setting TENANT_SUPPORTED_CHANNELS")
        void setTenantConfigs_TransientChannelFieldsStripped_BeforePersisting() throws Exception {
            // Arrange: client sends degraded + removedChannels in request — both must be stripped before DB write
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN")
                    .status(TenantStatusEnum.ACTIVE.name()).build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS,
                    objectMapper.readTree("{\"channels\":[\"BFM\"],\"degraded\":true,\"removedChannels\":[\"ELM\"]}"));
            SetTenantConfigRequestDTO request = SetTenantConfigRequestDTO.builder().configs(configs).build();

            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name())
                    .configValue("{\"channels\":[\"BFM\"]}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(systemManagementService.getSystemSupportedChannels()).thenReturn(List.of("BFM", "ELM", "PDU"));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            // Act
            tenantManagementService.setTenantConfigs(tenantId, request);

            // Assert: serialized value passed to upsertConfig must not contain degraded or removedChannels
            ArgumentCaptor<String> serializedCaptor = ArgumentCaptor.forClass(String.class);
            verify(tenantCommonRepository).upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS.name()), serializedCaptor.capture(), eq(100));
            String persisted = serializedCaptor.getValue();
            assertFalse(persisted.contains("degraded"), "degraded must not be persisted");
            assertFalse(persisted.contains("removedChannels"), "removedChannels must not be persisted");
        }

        @Test
        @DisplayName("Should auto-transition ONBOARDED tenant to CONFIGURED when all mandatory keys are set")
        void setTenantConfigs_allMandatoryKeysPresent_transitionsToConfigured() throws Exception {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN")
                    .status(TenantStatusEnum.ONBOARDED.name()).build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON,
                    objectMapper.readTree("{\"value\":\"template\"}"));

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name())
                    .configValue("{\"value\":\"template\"}")
                    .build();

            // All GENERIC mandatory keys already in DB
            List<ConfigDTO> allGenericConfigs = Arrays.stream(TenantConfigKeyEnum.values())
                    .filter(k -> k.getType() == TenantConfigKeyEnum.ConfigType.GENERIC && k.isMandatory())
                    .map(k -> ConfigDTO.builder().configKey(k.name()).build())
                    .toList();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(savedConfig));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(allGenericConfigs);
            when(tenantSchemaRepository.getSupportedLanguages("tenant_tn"))
                    .thenReturn(List.of(LanguageConfigDTO.builder().language("english").preference(1).build()));

            tenantManagementService.setTenantConfigs(tenantId, request(configs));

            verify(tenantCommonRepository).updateTenantStatus(tenantId, TenantStatusEnum.CONFIGURED, 100);
        }

        @Test
        @DisplayName("Should not auto-transition when tenant is ONBOARDED but some mandatory keys are missing")
        void setTenantConfigs_someMandatoryKeysMissing_doesNotTransition() throws Exception {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN")
                    .status(TenantStatusEnum.ONBOARDED.name()).build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON,
                    objectMapper.readTree("{\"value\":\"template\"}"));

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name())
                    .configValue("{\"value\":\"template\"}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(savedConfig));
            // Only one key configured — not all mandatory
            when(tenantCommonRepository.findConfigsByTenantId(tenantId))
                    .thenReturn(List.of(ConfigDTO.builder().configKey(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name()).build()));
            when(tenantSchemaRepository.getSupportedLanguages("tenant_tn")).thenReturn(Collections.emptyList());

            tenantManagementService.setTenantConfigs(tenantId, request(configs));

            verify(tenantCommonRepository, never()).updateTenantStatus(any(), any(), any());
        }

        @Test
        @DisplayName("Should not transition when all mandatory keys are set but tenant status is not ONBOARDED")
        void setTenantConfigs_allMandatoryKeysPresent_nonOnboardedStatus_doesNotTransition() throws Exception {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN")
                    .status(TenantStatusEnum.ACTIVE.name()).build();

            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON,
                    objectMapper.readTree("{\"value\":\"template\"}"));

            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name())
                    .configValue("{\"value\":\"template\"}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(savedConfig));

            tenantManagementService.setTenantConfigs(tenantId, request(configs));

            verify(tenantCommonRepository, never()).updateTenantStatus(any(), any(), any());
        }

        @Test
        @DisplayName("Should publish WaterNormUpdatedEvent when WATER_NORM key is set")
        void setTenantConfigs_withWaterNorm_publishesWaterNormUpdatedEvent() throws Exception {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("MP")
                    .status(TenantStatusEnum.ACTIVE.name()).build();
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.WATER_NORM, objectMapper.readTree("{\"value\": \"70\"}"));
            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.WATER_NORM.name())
                    .configValue("{\"value\":\"70\"}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.WATER_NORM.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(savedConfig));

            tenantManagementService.setTenantConfigs(tenantId, request(configs));

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertInstanceOf(org.arghyam.jalsoochak.tenant.event.WaterNormUpdatedEvent.class, captor.getValue());
            org.arghyam.jalsoochak.tenant.event.WaterNormUpdatedEvent event =
                    (org.arghyam.jalsoochak.tenant.event.WaterNormUpdatedEvent) captor.getValue();
            assertEquals(tenantId, event.getTenantId());
            assertEquals("MP", event.getStateCode());
            assertEquals(70, event.getWaterNorm());
        }

        @Test
        @DisplayName("Should not publish WaterNormUpdatedEvent when WATER_NORM key is absent")
        void setTenantConfigs_withoutWaterNorm_doesNotPublishWaterNormUpdatedEvent() throws Exception {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("MP")
                    .status(TenantStatusEnum.ACTIVE.name()).build();
            Map<TenantConfigKeyEnum, JsonNode> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON, objectMapper.readTree("{\"value\": \"tmpl\"}"));
            ConfigDTO savedConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name())
                    .configValue("{\"value\":\"tmpl\"}")
                    .build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(tenantId),
                    eq(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON.name()), anyString(), eq(100)))
                    .thenReturn(Optional.of(savedConfig));

            tenantManagementService.setTenantConfigs(tenantId, request(configs));

            verify(eventPublisher, never()).publishEvent(
                    any(org.arghyam.jalsoochak.tenant.event.WaterNormUpdatedEvent.class));
        }

        private SetTenantConfigRequestDTO request(Map<TenantConfigKeyEnum, JsonNode> configs) {
            return SetTenantConfigRequestDTO.builder().configs(configs).build();
        }
    }

    @Nested
    @DisplayName("Get Tenant Summary Tests")
    class GetTenantSummaryTests {

        @Test
        @DisplayName("Should return tenant summary from repository")
        void testGetTenantSummary_Success() {
            TenantSummaryResponseDTO summary = TenantSummaryResponseDTO.builder()
                    .totalTenants(10L)
                    .onboardedTenants(2L)
                    .configuredTenants(1L)
                    .activeTenants(5L)
                    .inactiveTenants(1L)
                    .suspendedTenants(0L)
                    .degradedTenants(0L)
                    .archivedTenants(1L)
                    .build();

            when(tenantCommonRepository.getTenantSummary()).thenReturn(summary);

            TenantSummaryResponseDTO result = tenantManagementService.getTenantSummary();

            assertNotNull(result);
            assertEquals(10L, result.getTotalTenants());
            assertEquals(2L, result.getOnboardedTenants());
            assertEquals(1L, result.getConfiguredTenants());
            assertEquals(5L, result.getActiveTenants());
            assertEquals(1L, result.getInactiveTenants());
            assertEquals(0L, result.getSuspendedTenants());
            assertEquals(0L, result.getDegradedTenants());
            assertEquals(1L, result.getArchivedTenants());
            verify(tenantCommonRepository).getTenantSummary();
        }
    }

    @Nested
    @DisplayName("Get Tenant Config Status Tests")
    class GetTenantConfigStatusTests {

        @Test
        @DisplayName("Should return config status with correct configured and pending counts")
        void testGetTenantConfigStatus_Success() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();
            List<ConfigDTO> dbConfigs = List.of(
                    ConfigDTO.builder().configKey(TenantConfigKeyEnum.TENANT_LOGO.name()).build()
            );

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(dbConfigs);
            when(tenantSchemaRepository.getSupportedLanguages("tenant_tn")).thenReturn(Collections.emptyList());

            TenantConfigStatusResponseDTO result = tenantManagementService.getTenantConfigStatus(tenantId);

            assertNotNull(result);
            assertEquals(tenantId, result.getTenantId());
            int total = TenantConfigKeyEnum.values().length;
            assertEquals(total, result.getSummary().getTotal());
            assertEquals(1, result.getSummary().getConfigured());
            assertEquals(total - 1, result.getSummary().getPending());
            assertEquals(ConfigStatusEnum.CONFIGURED, result.getConfigs().get(TenantConfigKeyEnum.TENANT_LOGO).getStatus());
            assertTrue(result.getConfigs().get(TenantConfigKeyEnum.TENANT_LOGO).isMandatory());
            assertFalse(result.getConfigs().get(TenantConfigKeyEnum.GLIFIC_MESSAGE_TEMPLATES).isMandatory(),
                    "GLIFIC_MESSAGE_TEMPLATES is optional");
            assertFalse(result.getConfigs().get(TenantConfigKeyEnum.STATE_IT_SYSTEM_CONNECTION).isMandatory(),
                    "STATE_IT_SYSTEM_CONNECTION is optional");
            assertFalse(result.getConfigs().get(TenantConfigKeyEnum.STATE_DATA_RECONCILIATION_TIME).isMandatory(),
                    "STATE_DATA_RECONCILIATION_TIME is optional");
            assertFalse(result.getConfigs().get(TenantConfigKeyEnum.EMAIL_TEMPLATE_JSON).isMandatory(),
                    "EMAIL_TEMPLATE_JSON is optional");
            verify(tenantCommonRepository).findById(tenantId);
            verify(tenantCommonRepository).findConfigsByTenantId(tenantId);
        }

        @Test
        @DisplayName("Should mark SUPPORTED_LANGUAGES as CONFIGURED when schema languages exist")
        void testGetTenantConfigStatus_SupportedLanguagesConfigured() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("TN").build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantCommonRepository.findConfigsByTenantId(tenantId)).thenReturn(Collections.emptyList());
            when(tenantSchemaRepository.getSupportedLanguages("tenant_tn"))
                    .thenReturn(List.of(LanguageConfigDTO.builder().language("english").preference(1).build()));

            TenantConfigStatusResponseDTO result = tenantManagementService.getTenantConfigStatus(tenantId);

            assertNotNull(result);
            assertEquals(ConfigStatusEnum.CONFIGURED,
                    result.getConfigs().get(TenantConfigKeyEnum.SUPPORTED_LANGUAGES).getStatus());
            assertEquals(1, result.getSummary().getConfigured());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found")
        void testGetTenantConfigStatus_TenantNotFound() {
            when(tenantCommonRepository.findById(999)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.getTenantConfigStatus(999));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for system tenant (id=0)")
        void testGetTenantConfigStatus_SystemTenantRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.getTenantConfigStatus(TenantConstants.SYSTEM_TENANT_ID));
        }
    }

    @Nested
    @DisplayName("Location Hierarchy Tests")
    class LocationHierarchyTests {
        
        @Test
        @DisplayName("Should get location hierarchy successfully for LGD")
        void testGetLocationHierarchy_Success() {
            // Arrange
            Integer tenantId = 1;
            String hierarchyType = "LGD";
            
            TenantResponseDTO tenant = TenantResponseDTO.builder()
                    .id(tenantId)
                    .stateCode("mp")
                    .build();
            
            List<LocationLevelConfigDTO> levels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );

            LocationConfigDTO locationConfig = LocationConfigDTO.builder()
                    .locationHierarchy(levels)
                    .build();
            
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantSchemaRepository.getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD))
                    .thenReturn(locationConfig);
            
            // Act
            LocationHierarchyResponseDTO result = tenantManagementService.getLocationHierarchy(tenantId, hierarchyType);
            
            // Assert
            assertNotNull(result);
            assertEquals("LGD", result.getHierarchyType());
            assertEquals(2, result.getLevels().size());
            verify(tenantCommonRepository).findById(tenantId);
            verify(tenantSchemaRepository).getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD);
        }
        
        @Test
        @DisplayName("Should throw exception when tenant not found")
        void testGetLocationHierarchy_TenantNotFound() {
            // Arrange
            Integer tenantId = 999;
            String hierarchyType = "LGD";
            
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.empty());
            
            // Act & Assert
            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.getLocationHierarchy(tenantId, hierarchyType));
            verify(tenantCommonRepository).findById(tenantId);
        }
        
        @Test
        @DisplayName("Should get location children successfully for root level (parentId=null)")
        void testGetLocationChildren_RootLevel_Success() {
            // Arrange
            Integer tenantId = 1;
            String hierarchyType = "LGD";
            Integer parentId = null;
            
            TenantResponseDTO tenant = TenantResponseDTO.builder()
                    .id(tenantId)
                    .stateCode("mp")
                    .build();
            
            List<LocationResponseDTO> children = List.of(
                    LocationResponseDTO.builder().id(1).uuid("uuid-1").title("Madhya Pradesh").status(StatusEnum.ACTIVE.getCode()).build()
            );
            
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantSchemaRepository.findLgdLocationsByParentId("tenant_mp", parentId))
                    .thenReturn(children);
            
            // Act
            List<LocationResponseDTO> result = tenantManagementService.getLocationChildren(tenantId, hierarchyType, parentId);
            
            // Assert
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("Madhya Pradesh", result.get(0).getTitle());
            verify(tenantCommonRepository).findById(tenantId);
            verify(tenantSchemaRepository).findLgdLocationsByParentId("tenant_mp", parentId);
        }
        
        @Test
        @DisplayName("Should get location children successfully for DEPARTMENT hierarchy")
        void testGetLocationChildren_Department_Success() {
            // Arrange
            Integer tenantId = 1;
            String hierarchyType = "DEPARTMENT";
            Integer parentId = 1;
            
            TenantResponseDTO tenant = TenantResponseDTO.builder()
                    .id(tenantId)
                    .stateCode("mp")
                    .build();
            
            List<LocationResponseDTO> children = List.of(
                    LocationResponseDTO.builder().id(10).uuid("uuid-10").title("Zone A").parentId(1).status(StatusEnum.ACTIVE.getCode()).build(),
                    LocationResponseDTO.builder().id(11).uuid("uuid-11").title("Zone B").parentId(1).status(StatusEnum.ACTIVE.getCode()).build()
            );
            
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantSchemaRepository.findDepartmentLocationsByParentId("tenant_mp", parentId))
                    .thenReturn(children);
            
            // Act
            List<LocationResponseDTO> result = tenantManagementService.getLocationChildren(tenantId, hierarchyType, parentId);
            
            // Assert
            assertNotNull(result);
            assertEquals(2, result.size());
            assertEquals("Zone A", result.get(0).getTitle());
            verify(tenantCommonRepository).findById(tenantId);
            verify(tenantSchemaRepository).findDepartmentLocationsByParentId("tenant_mp", parentId);
        }
        
        @Test
        @DisplayName("Should return empty list for children when no results found")
        void testGetLocationChildren_EmptyResult() {
            // Arrange
            Integer tenantId = 1;
            String hierarchyType = "LGD";
            Integer parentId = 999;
            
            TenantResponseDTO tenant = TenantResponseDTO.builder()
                    .id(tenantId)
                    .stateCode("mp")
                    .build();
            
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantSchemaRepository.findLgdLocationsByParentId("tenant_mp", parentId))
                    .thenReturn(Collections.emptyList());
            
            // Act
            List<LocationResponseDTO> result = tenantManagementService.getLocationChildren(tenantId, hierarchyType, parentId);
            
            // Assert
            assertNotNull(result);
            assertTrue(result.isEmpty());
            verify(tenantSchemaRepository).findLgdLocationsByParentId("tenant_mp", parentId);
        }
        
        @Test
        @DisplayName("Should throw exception when tenant not found for location children")
        void testGetLocationChildren_TenantNotFound() {
            // Arrange
            Integer tenantId = 999;
            String hierarchyType = "LGD";
            Integer parentId = 0;

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.getLocationChildren(tenantId, hierarchyType, parentId));
            verify(tenantCommonRepository).findById(tenantId);
        }

        @Test
        @DisplayName("Should return edit constraints with structuralChangesAllowed=true when no seeded data")
        void testGetLocationHierarchyEditConstraints_NoSeededData() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantSchemaRepository.countSeededLocationData("tenant_mp", RegionTypeEnum.LGD)).thenReturn(0L);

            // Act
            LocationHierarchyEditConstraintsResponseDTO result =
                    tenantManagementService.getLocationHierarchyEditConstraints(tenantId, "LGD");

            // Assert
            assertNotNull(result);
            assertEquals("LGD", result.getHierarchyType());
            assertTrue(result.isStructuralChangesAllowed());
            assertEquals(0L, result.getSeededRecordCount());
        }

        @Test
        @DisplayName("Should return edit constraints with structuralChangesAllowed=false when seeded data exists")
        void testGetLocationHierarchyEditConstraints_SeededDataExists() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(tenantSchemaRepository.countSeededLocationData("tenant_mp", RegionTypeEnum.DEPARTMENT))
                    .thenReturn(342L);

            // Act
            LocationHierarchyEditConstraintsResponseDTO result =
                    tenantManagementService.getLocationHierarchyEditConstraints(tenantId, "DEPARTMENT");

            // Assert
            assertNotNull(result);
            assertEquals("DEPARTMENT", result.getHierarchyType());
            assertFalse(result.isStructuralChangesAllowed());
            assertEquals(342L, result.getSeededRecordCount());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found for edit constraints")
        void testGetLocationHierarchyEditConstraints_TenantNotFound() {
            when(tenantCommonRepository.findById(999)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.getLocationHierarchyEditConstraints(999, "LGD"));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for invalid hierarchy type in edit constraints")
        void testGetLocationHierarchyEditConstraints_InvalidType() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.getLocationHierarchyEditConstraints(tenantId, "INVALID"));
        }

        @Test
        @DisplayName("Should do full structural update (delete+insert) when no seeded data exists")
        void testUpdateLocationHierarchy_StructuralChange_NoSeededData() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            List<LocationLevelConfigDTO> existingLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build()
            );
            List<LocationLevelConfigDTO> newLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantSchemaRepository.getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD))
                    .thenReturn(LocationConfigDTO.builder().locationHierarchy(existingLevels).build());
            // rewriteLocationHierarchyIfNoSeededData is a void method; default mock does nothing (no seeded data)

            // Act
            LocationHierarchyResponseDTO result =
                    tenantManagementService.updateLocationHierarchy(tenantId, "LGD", newLevels);

            // Assert
            assertNotNull(result);
            assertEquals("LGD", result.getHierarchyType());
            assertEquals(2, result.getLevels().size());
            verify(tenantSchemaRepository).rewriteLocationHierarchyIfNoSeededData(
                    "tenant_mp", RegionTypeEnum.LGD, newLevels, 100);
            verify(tenantSchemaRepository, never()).updateLevelNames(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw LocationHierarchyStructureLockedException when structural change attempted with seeded data")
        void testUpdateLocationHierarchy_StructuralChange_SeededDataExists() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            List<LocationLevelConfigDTO> existingLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build()
            );
            // Incoming adds a level — structural change
            List<LocationLevelConfigDTO> newLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantSchemaRepository.getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD))
                    .thenReturn(LocationConfigDTO.builder().locationHierarchy(existingLevels).build());
            // Simulate seeded data by having the atomic method throw the locked exception
            doThrow(new LocationHierarchyStructureLockedException("LGD", 1842L))
                    .when(tenantSchemaRepository).rewriteLocationHierarchyIfNoSeededData(
                            eq("tenant_mp"), eq(RegionTypeEnum.LGD), any(), anyInt());

            // Act & Assert
            assertThrows(LocationHierarchyStructureLockedException.class,
                    () -> tenantManagementService.updateLocationHierarchy(tenantId, "LGD", newLevels));

            verify(tenantSchemaRepository, never()).setLocationHierarchy(any(), any(), any(), any());
            verify(tenantSchemaRepository, never()).updateLevelNames(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should do name-only update (updateLevelNames) when only level names changed with seeded data")
        void testUpdateLocationHierarchy_NameOnlyChange_SeededDataExists() {
            // Arrange
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            List<LocationLevelConfigDTO> existingLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );
            // Same level numbers (1, 2) but renamed — not a structural change
            List<LocationLevelConfigDTO> renamedLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Rajya").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Zila").build())).build()
            );

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantSchemaRepository.getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD))
                    .thenReturn(LocationConfigDTO.builder().locationHierarchy(existingLevels).build());

            // Act
            LocationHierarchyResponseDTO result =
                    tenantManagementService.updateLocationHierarchy(tenantId, "LGD", renamedLevels);

            // Assert
            assertNotNull(result);
            assertEquals("LGD", result.getHierarchyType());
            verify(tenantSchemaRepository).updateLevelNames("tenant_mp", RegionTypeEnum.LGD, renamedLevels, 100);
            verify(tenantSchemaRepository, never()).setLocationHierarchy(any(), any(), any(), any());
            verify(tenantSchemaRepository, never()).countSeededLocationData(any(), any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found for updateLocationHierarchy")
        void testUpdateLocationHierarchy_TenantNotFound() {
            when(tenantCommonRepository.findById(999)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.updateLocationHierarchy(999, "LGD",
                            List.of(LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build())));
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for invalid hierarchy type in updateLocationHierarchy")
        void testUpdateLocationHierarchy_InvalidType() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.updateLocationHierarchy(tenantId, "INVALID",
                            List.of(LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build())));
        }

        // --- System-tenant guard tests (tenantId = 0) ---


        @Test
        @DisplayName("Should reject getLocationHierarchy for system tenant (tenantId=0)")
        void testGetLocationHierarchy_SystemTenant_Rejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.getLocationHierarchy(TenantConstants.SYSTEM_TENANT_ID, "LGD"));
            verify(tenantCommonRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should reject getLocationChildren for system tenant (tenantId=0)")
        void testGetLocationChildren_SystemTenant_Rejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.getLocationChildren(TenantConstants.SYSTEM_TENANT_ID, "LGD", null));
            verify(tenantCommonRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should reject getLocationHierarchyEditConstraints for system tenant (tenantId=0)")
        void testGetLocationHierarchyEditConstraints_SystemTenant_Rejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.getLocationHierarchyEditConstraints(TenantConstants.SYSTEM_TENANT_ID, "LGD"));
            verify(tenantCommonRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should reject updateLocationHierarchy for system tenant (tenantId=0)")
        void testUpdateLocationHierarchy_SystemTenant_Rejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.updateLocationHierarchy(TenantConstants.SYSTEM_TENANT_ID, "LGD",
                            List.of(LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build())));
            verify(tenantCommonRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when levels list contains a null entry")
        void testUpdateLocationHierarchy_NullLevelEntry_Rejected() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

            List<LocationLevelConfigDTO> levelsWithNull = new java.util.ArrayList<>();
            levelsWithNull.add(LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build());
            levelsWithNull.add(null);

            assertThrows(InvalidConfigValueException.class,
                    () -> tenantManagementService.updateLocationHierarchy(tenantId, "LGD", levelsWithNull));
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when a level entry has a null level field")
        void testUpdateLocationHierarchy_NullLevelField_Rejected() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

            List<LocationLevelConfigDTO> levelsWithNullField = List.of(
                    LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build(),
                    LocationLevelConfigDTO.builder().level(null).levelName(List.of()).build());

            assertThrows(InvalidConfigValueException.class,
                    () -> tenantManagementService.updateLocationHierarchy(tenantId, "LGD", levelsWithNullField));
        }

        @Test
        @DisplayName("Should throw InvalidConfigValueException when level numbers contain duplicates")
        void testUpdateLocationHierarchy_DuplicateLevelNumbers_Rejected() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

            List<LocationLevelConfigDTO> levelsWithDuplicates = List.of(
                    LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build(),
                    LocationLevelConfigDTO.builder().level(1).levelName(List.of()).build());

            InvalidConfigValueException ex = assertThrows(InvalidConfigValueException.class,
                    () -> tenantManagementService.updateLocationHierarchy(tenantId, "LGD", levelsWithDuplicates));
            assertTrue(ex.getMessage().contains("unique"));
        }

        @Test
        @DisplayName("Should publish TenantLocationHierarchyUpdatedEvent on name-only change")
        void updateLocationHierarchy_nameOnlyChange_publishesHierarchyUpdatedEvent() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            List<LocationLevelConfigDTO> existingLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build());
            List<LocationLevelConfigDTO> renamedLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Rajya").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Zila").build())).build());

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantSchemaRepository.getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD))
                    .thenReturn(LocationConfigDTO.builder().locationHierarchy(existingLevels).build());

            tenantManagementService.updateLocationHierarchy(tenantId, "LGD", renamedLevels);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertInstanceOf(org.arghyam.jalsoochak.tenant.event.TenantLocationHierarchyUpdatedEvent.class, captor.getValue());
            org.arghyam.jalsoochak.tenant.event.TenantLocationHierarchyUpdatedEvent event =
                    (org.arghyam.jalsoochak.tenant.event.TenantLocationHierarchyUpdatedEvent) captor.getValue();
            assertEquals(tenantId, event.getTenantId());
            assertEquals("mp", event.getStateCode());
            assertEquals("LGD", event.getHierarchyType());
            assertEquals(renamedLevels, event.getLevels());
        }

        @Test
        @DisplayName("Should not publish TenantLocationHierarchyUpdatedEvent on structural change")
        void updateLocationHierarchy_structuralChange_doesNotPublishHierarchyUpdatedEvent() {
            Integer tenantId = 1;
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(tenantId).stateCode("mp").build();
            List<LocationLevelConfigDTO> existingLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build());
            // Different level number — structural change
            List<LocationLevelConfigDTO> newLevels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build());

            when(tenantCommonRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantSchemaRepository.getLocationHierarchy("tenant_mp", RegionTypeEnum.LGD))
                    .thenReturn(LocationConfigDTO.builder().locationHierarchy(existingLevels).build());

            tenantManagementService.updateLocationHierarchy(tenantId, "LGD", newLevels);

            verify(eventPublisher, never()).publishEvent(
                    any(org.arghyam.jalsoochak.tenant.event.TenantLocationHierarchyUpdatedEvent.class));
        }
    }

    @Nested
    @DisplayName("Set Tenant Logo Tests")
    class SetTenantLogoTests {

        private static final Integer TENANT_ID = 1;
        private static final TenantResponseDTO TENANT =
                TenantResponseDTO.builder().id(TENANT_ID).stateCode("TN").build();

        @BeforeEach
        void initTransactionSync() {
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void clearTransactionSync() {
            TransactionSynchronizationManager.clearSynchronization();
        }

        private void fireAfterCommit() {
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        }

        private void fireAfterRollback() {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        }

        // --- FileSource tests ---

        @Test
        @DisplayName("Should upload file and return updated config when no previous logo exists")
        void setTenantLogo_fileSource_noExistingLogo_success() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"logos/1/stub.png\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.empty());
            when(objectStorageService.upload(anyString(), any(), anyLong(), eq("image/png"))).thenReturn("logos/1/stub.png");
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            TenantConfigResponseDTO result = tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file));
            fireAfterCommit();

            assertNotNull(result);
            assertEquals(TENANT_ID, result.getTenantId());
            assertTrue(result.getConfigs().containsKey(TenantConfigKeyEnum.TENANT_LOGO));
            String storedKey = ((SimpleConfigValueDTO) result.getConfigs().get(TenantConfigKeyEnum.TENANT_LOGO)).getValue();
            assertTrue(storedKey.startsWith("logos/" + TENANT_ID + "/"));
            assertTrue(storedKey.endsWith(".png"));
            verify(objectStorageService).upload(anyString(), any(), anyLong(), eq("image/png"));
            verify(objectStorageService, never()).delete(any());
        }

        @Test
        @DisplayName("Should delete old managed object after successful file upload")
        void setTenantLogo_fileSource_withManagedOldLogo_deletesOldObject() throws Exception {
            String oldKey = "logos/1/old-uuid.png";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());
            String newObjectKey = "logos/1/new-uuid.png";
            ConfigDTO oldConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + oldKey + "\"}")
                    .build();
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + newObjectKey + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.of(oldConfig));
            when(objectStorageService.upload(anyString(), any(), anyLong(), eq("image/png"))).thenReturn(newObjectKey);
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file));
            fireAfterCommit();

            verify(objectStorageService).delete(oldKey);
        }

        @Test
        @DisplayName("Should skip delete when previous logo is external (file source)")
        void setTenantLogo_fileSource_externalOldLogo_skipsDelete() throws Exception {
            String externalOldUrl = "https://cdn.example.com/logo.png";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());
            String newObjectKey = "logos/1/new-uuid.png";
            ConfigDTO oldConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + externalOldUrl + "\"}")
                    .build();
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + newObjectKey + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.of(oldConfig));
            when(objectStorageService.upload(anyString(), any(), anyLong(), eq("image/png"))).thenReturn(newObjectKey);
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file));
            fireAfterCommit();

            verify(objectStorageService, never()).delete(any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found (file source)")
        void setTenantLogo_fileSource_tenantNotFound_throwsResourceNotFoundException() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for empty file")
        void setTenantLogo_fileSource_emptyFile_throwsIllegalArgumentException() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", new byte[0]);

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));

            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when file exceeds 2 MB")
        void setTenantLogo_fileSource_fileTooLarge_throwsIllegalArgumentException() {
            byte[] oversized = new byte[2 * 1024 * 1024 + 1];
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", oversized);

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));

            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for unsupported content type")
        void setTenantLogo_fileSource_unsupportedType_throwsIllegalArgumentException() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.gif", "image/gif", "fake-image".getBytes());

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));

            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Should propagate StorageException when upload fails")
        void setTenantLogo_fileSource_storageUploadFails_throwsStorageException() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.empty());
            when(objectStorageService.upload(anyString(), any(), anyLong(), eq("image/png")))
                    .thenThrow(new StorageException("S3 error"));

            assertThrows(StorageException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            verify(tenantCommonRepository, never()).upsertConfig(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for SVG content type (XSS vector)")
        void setTenantLogo_fileSource_svgType_throwsIllegalArgumentException() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.svg", "image/svg+xml", "<svg></svg>".getBytes());

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));

            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
        }

        @Test
        @DisplayName("Should delete uploaded object when transaction is rolled back (file source)")
        void setTenantLogo_fileSource_rollback_deletesUploadedObject() throws Exception {
            String uploadedKey = "logos/1/new-uuid.png";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + uploadedKey + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.empty());
            when(objectStorageService.upload(anyString(), any(), anyLong(), eq("image/png"))).thenReturn(uploadedKey);
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file));
            fireAfterRollback();

            verify(objectStorageService).delete(uploadedKey);
        }

        @Test
        @DisplayName("Should delete uploaded object when resolveCurrentUserId fails after upload")
        void setTenantLogo_fileSource_resolveUserFails_deletesUploadedObject() throws Exception {
            String uploadedKey = "logos/1/orphan-uuid.png";
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", "fake-image-bytes".getBytes());

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.empty());
            when(objectStorageService.upload(anyString(), any(), anyLong(), eq("image/png"))).thenReturn(uploadedKey);
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("unknown-uuid");
            when(tenantCommonRepository.findUserIdByUuid("unknown-uuid")).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.FileSource(file)));
            fireAfterRollback();

            verify(objectStorageService).delete(uploadedKey);
        }

        // --- UrlSource tests ---

        @Test
        @DisplayName("Should store external URL and return updated config when no previous logo exists")
        void setTenantLogo_urlSource_noExistingLogo_success() {
            String externalUrl = "https://cdn.example.com/logo.png";
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + externalUrl + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.empty());
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            TenantConfigResponseDTO result = tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.UrlSource(externalUrl));
            fireAfterCommit();

            assertNotNull(result);
            assertEquals(TENANT_ID, result.getTenantId());
            assertEquals(externalUrl,
                    ((SimpleConfigValueDTO) result.getConfigs().get(TenantConfigKeyEnum.TENANT_LOGO)).getValue());
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
            verify(objectStorageService, never()).delete(any());
        }

        @Test
        @DisplayName("Should delete old managed object when replacing with external URL")
        void setTenantLogo_urlSource_withManagedOldLogo_deletesOldObject() {
            String oldKey = "logos/1/old-uuid.png";
            String externalUrl = "https://cdn.example.com/logo.png";
            ConfigDTO oldConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + oldKey + "\"}")
                    .build();
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + externalUrl + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.of(oldConfig));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.UrlSource(externalUrl));
            fireAfterCommit();

            verify(objectStorageService).delete(oldKey);
        }

        @Test
        @DisplayName("Should skip delete when previous logo is also external (url source)")
        void setTenantLogo_urlSource_externalOldLogo_skipsDelete() {
            String oldExternalUrl = "https://old.cdn.example.com/logo.png";
            String newExternalUrl = "https://new.cdn.example.com/logo.png";
            ConfigDTO oldConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + oldExternalUrl + "\"}")
                    .build();
            ConfigDTO saved = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + newExternalUrl + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO")).thenReturn(Optional.of(oldConfig));
            when(SecurityUtils.getCurrentUserUuid()).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(100));
            when(tenantCommonRepository.upsertConfig(eq(TENANT_ID), eq("TENANT_LOGO"), anyString(), eq(100)))
                    .thenReturn(Optional.of(saved));

            tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.UrlSource(newExternalUrl));
            fireAfterCommit();

            verify(objectStorageService, never()).delete(any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for invalid URL scheme")
        void setTenantLogo_urlSource_invalidScheme_throwsIllegalArgumentException() {
            // Validation now happens in UrlSource canonical constructor — service is never reached.
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.UrlSource("ftp://example.com/logo.png")));
            verify(tenantCommonRepository, never()).upsertConfig(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for malformed URL")
        void setTenantLogo_urlSource_malformedUrl_throwsIllegalArgumentException() {
            // Validation now happens in UrlSource canonical constructor — service is never reached.
            assertThrows(IllegalArgumentException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID, new LogoSource.UrlSource("not a url")));
            verify(tenantCommonRepository, never()).upsertConfig(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found (url source)")
        void setTenantLogo_urlSource_tenantNotFound_throwsResourceNotFoundException() {
            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.setTenantLogo(TENANT_ID,
                            new LogoSource.UrlSource("https://cdn.example.com/logo.png")));
            verify(objectStorageService, never()).upload(any(), any(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Resolve Tenant Logo Tests")
    class ResolveTenantLogoTests {

        private static final Integer TENANT_ID = 1;
        private static final TenantResponseDTO TENANT =
                TenantResponseDTO.builder().id(TENANT_ID).stateCode("TN").build();

        @Test
        @DisplayName("Should return Managed result with stream and correct content type for a PNG object key")
        void resolveTenantLogo_managedPng_returnsManagedResult() {
            String objectKey = "logos/1/uuid.png";
            ConfigDTO logoConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + objectKey + "\"}")
                    .build();
            ByteArrayInputStream fakeStream = new ByteArrayInputStream("fake-image".getBytes());

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO"))
                    .thenReturn(Optional.of(logoConfig));
            when(objectStorageService.download(objectKey)).thenReturn(fakeStream);

            TenantLogoResult result = tenantManagementService.resolveTenantLogo(TENANT_ID);

            assertNotNull(result);
            assertInstanceOf(TenantLogoResult.Managed.class, result);
            assertEquals("image/png", ((TenantLogoResult.Managed) result).contentType());
            verify(objectStorageService).download(objectKey);
        }

        @Test
        @DisplayName("Should return External result when logo value is an external URL")
        void resolveTenantLogo_externalUrl_returnsExternalResult() {
            String externalUrl = "https://cdn.example.com/logo.png";
            ConfigDTO logoConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + externalUrl + "\"}")
                    .build();

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO"))
                    .thenReturn(Optional.of(logoConfig));

            TenantLogoResult result = tenantManagementService.resolveTenantLogo(TENANT_ID);

            assertNotNull(result);
            assertInstanceOf(TenantLogoResult.External.class, result);
            assertEquals(externalUrl, ((TenantLogoResult.External) result).redirectUrl());
            verify(objectStorageService, never()).download(any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when tenant not found")
        void resolveTenantLogo_tenantNotFound_throwsResourceNotFoundException() {
            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.resolveTenantLogo(TENANT_ID));
        }

        @Test
        @DisplayName("Should throw StorageException when download stream throws IOException")
        void resolveTenantLogo_downloadIOException_throwsStorageException() throws Exception {
            String objectKey = "logos/1/uuid.png";
            ConfigDTO logoConfig = ConfigDTO.builder()
                    .configKey(TenantConfigKeyEnum.TENANT_LOGO.name())
                    .configValue("{\"value\":\"" + objectKey + "\"}")
                    .build();
            InputStream brokenStream = mock(InputStream.class);
            when(brokenStream.readAllBytes()).thenThrow(new IOException("S3 connection reset"));

            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO"))
                    .thenReturn(Optional.of(logoConfig));
            when(objectStorageService.download(objectKey)).thenReturn(brokenStream);

            assertThrows(StorageException.class,
                    () -> tenantManagementService.resolveTenantLogo(TENANT_ID));
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when logo not configured")
        void resolveTenantLogo_logoNotConfigured_throwsResourceNotFoundException() {
            when(tenantCommonRepository.findById(TENANT_ID)).thenReturn(Optional.of(TENANT));
            when(tenantCommonRepository.findConfigByTenantAndKey(TENANT_ID, "TENANT_LOGO"))
                    .thenReturn(Optional.empty());

            assertThrows(ResourceNotFoundException.class,
                    () -> tenantManagementService.resolveTenantLogo(TENANT_ID));
        }
    }
}