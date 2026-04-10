package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.arghyam.jalsoochak.tenant.config.properties.TenantDefaultsProperties;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantSchemaRepository;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.arghyam.jalsoochak.tenant.storage.ObjectStorageService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
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
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for Single Tenant Mode (STM) functionality in TenantManagementServiceImpl.
 * Covers STM-specific behavior for tenant creation and enforcement.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Tenant Management Service - Single Tenant Mode Tests")
public class TenantManagementServiceSingleTenantModeTest {

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private TenantSchemaRepository tenantSchemaRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private AppProperties appProperties;

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

    private TenantManagementServiceImpl tenantManagementService;
    private MockedStatic<SecurityUtils> mockedSecurityUtils;

    @BeforeEach
    void setUp() {
        mockedSecurityUtils = mockStatic(SecurityUtils.class);
        tenantManagementService = new TenantManagementServiceImpl(
                tenantCommonRepository,
                tenantSchemaRepository,
                objectMapper,
                appProperties,
                tenantDefaults,
                eventPublisher,
                schedulerManager,
                objectStorageService,
                systemManagementService);
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtils.close();
    }

    @Nested
    @DisplayName("Single Tenant Mode - Enforcement Tests")
    class SingleTenantModeEnforcementTests {

        @Test
        @DisplayName("Should reject second tenant creation in STM immediately without checking state code")
        void testSecondTenantCreationInSTMThrowsConflictBeforeStateCodeCheck() {
            // Arrange
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.countNonDeletedTenants()).thenReturn(1);

            CreateTenantRequestDTO request = new CreateTenantRequestDTO();
            request.setStateCode("TN");
            request.setName("Tamil Nadu");
            request.setLgdCode(2);

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> tenantManagementService.createTenant(request));

            assertEquals("A tenant already exists. Only one tenant is allowed in Single Tenant Mode.",
                    exception.getMessage());
            verify(tenantCommonRepository).countNonDeletedTenants();
            // Verify that we never check for duplicate state code (STM check happens first)
            verify(tenantCommonRepository, never()).findByStateCode(anyString());
            verify(tenantCommonRepository, never()).createTenant(any(), anyInt());
        }


        @Test
        @DisplayName("Should skip STM check when in Multi-Tenant Mode")
        void testMultiTenantModeSkipsSTMCheck() {
            // Arrange
            when(appProperties.isSingleTenantMode()).thenReturn(false);

            CreateTenantRequestDTO request = new CreateTenantRequestDTO();
            request.setStateCode("KA");
            request.setName("Karnataka");
            request.setLgdCode(1);

            // Act
            // When in MTM, the countNonDeletedTenants() should never be called
            try {
                tenantManagementService.createTenant(request);
            } catch (Exception e) {
                // Expected - we're just testing the STM check is skipped
                // Other validation may fail, which is fine
            }

            // Assert
            verify(appProperties).isSingleTenantMode();
            verify(tenantCommonRepository, never()).countNonDeletedTenants();
        }

        @Test
        @DisplayName("Should enforce STM check before duplicate state code check")
        void testSTMCheckBeforeDuplicateStateCodeCheck() {
            // Arrange
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.countNonDeletedTenants()).thenReturn(1);

            CreateTenantRequestDTO request = new CreateTenantRequestDTO();
            request.setStateCode("KA");
            request.setName("Karnataka");
            request.setLgdCode(1);

            // Act & Assert
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> tenantManagementService.createTenant(request));

            assertEquals("A tenant already exists. Only one tenant is allowed in Single Tenant Mode.",
                    exception.getMessage());
            // Verify STM check was performed
            verify(tenantCommonRepository).countNonDeletedTenants();
            // Verify state code check was NOT performed (STM check failed first)
            verify(tenantCommonRepository, never()).findByStateCode(anyString());
        }

        @Test
        @DisplayName("DataIntegrityViolationException in STM is wrapped as single-tenant conflict")
        void testDataIntegrityViolationInSTMWrapsAsIllegalState() {
            when(appProperties.isSingleTenantMode()).thenReturn(true);
            when(tenantCommonRepository.countNonDeletedTenants()).thenReturn(0);
            when(tenantCommonRepository.findByStateCode(anyString())).thenReturn(Optional.empty());
            mockedSecurityUtils.when(SecurityUtils::getCurrentUserUuid).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.createTenant(any(), anyInt()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            CreateTenantRequestDTO request = new CreateTenantRequestDTO();
            request.setStateCode("MP");
            request.setName("Madhya Pradesh");
            request.setLgdCode(5);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> tenantManagementService.createTenant(request));

            assertEquals("A tenant already exists. Only one tenant is allowed in Single Tenant Mode.",
                    ex.getMessage());
        }

        @Test
        @DisplayName("DataIntegrityViolationException in MTM is wrapped as duplicate state code conflict")
        void testDataIntegrityViolationInMTMWrapsAsDuplicateStateCode() {
            when(appProperties.isSingleTenantMode()).thenReturn(false);
            when(tenantCommonRepository.findByStateCode(anyString())).thenReturn(Optional.empty());
            mockedSecurityUtils.when(SecurityUtils::getCurrentUserUuid).thenReturn("user-uuid");
            when(tenantCommonRepository.findUserIdByUuid("user-uuid")).thenReturn(Optional.of(1));
            when(tenantCommonRepository.createTenant(any(), anyInt()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            CreateTenantRequestDTO request = new CreateTenantRequestDTO();
            request.setStateCode("MP");
            request.setName("Madhya Pradesh");
            request.setLgdCode(5);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> tenantManagementService.createTenant(request));

            assertEquals("Tenant with state code 'MP' already exists", ex.getMessage());
        }
    }
}
