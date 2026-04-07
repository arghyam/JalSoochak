package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.arghyam.jalsoochak.tenant.config.properties.TenantDefaultsProperties;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantSchemaRepository;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.arghyam.jalsoochak.tenant.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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

    @BeforeEach
    void setUp() {
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
    }
}
