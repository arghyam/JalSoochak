package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;

import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
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

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(TenantLifecycleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Tenant Lifecycle Controller Tests")
class TenantLifecycleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Create Tenant")
    class CreateTenantTests {
        @Test
        void createTenant_Success() throws Exception {
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .name("Test Tenant")
                    .stateCode("KA")
                    .lgdCode(29)
                    .build();

            TenantResponseDTO response = TenantResponseDTO.builder().id(1).name("Test Tenant").stateCode("KA")
                    .status(TenantStatusEnum.ONBOARDED.name()).build();

            when(tenantManagementService.createTenant(any(CreateTenantRequestDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("Tenant created successfully"))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.name").value("Test Tenant"));

            verify(tenantManagementService, times(1)).createTenant(any());
        }

        @Test
        void createTenant_AlreadyExists() throws Exception {
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .name("Karnataka")
                    .stateCode("KA")
                    .lgdCode(29)
                    .build();

            when(tenantManagementService.createTenant(any()))
                    .thenThrow(new IllegalStateException("Tenant already exists for stateCode: KA"));

            mockMvc.perform(post("/api/v1/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));

            verify(tenantManagementService).createTenant(any());
        }

        @Test
        void createTenant_InternalError() throws Exception {
            CreateTenantRequestDTO request = CreateTenantRequestDTO.builder()
                    .name("Karnataka")
                    .stateCode("KA")
                    .lgdCode(29)
                    .build();

            when(tenantManagementService.createTenant(any()))
                    .thenThrow(new RuntimeException("Schema provisioning failed"));

            mockMvc.perform(post("/api/v1/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError());
        }

        @Test
        void createTenant_MissingStateCode() throws Exception {
            String requestJson = "{\"name\":\"Test\",\"lgdCode\":29}";

            mockMvc.perform(post("/api/v1/tenants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson))
                    .andExpect(status().is4xxClientError());

            verify(tenantManagementService, never()).createTenant(any());
        }
    }

    @Nested
    @DisplayName("Get All Tenants")
    class GetAllTenantsTests {
        @Test
        void getAllTenants_Success() throws Exception {
            TenantResponseDTO tenant1 = TenantResponseDTO.builder().id(1).name("Test Tenant").stateCode("KA").build();
            TenantResponseDTO tenant2 = TenantResponseDTO.builder().id(2).name("Test Tenant 2").stateCode("KL").build();
            PageResponseDTO<TenantResponseDTO> pageResponse = PageResponseDTO.<TenantResponseDTO>builder()
                    .content(List.of(tenant1, tenant2))
                    .number(0)
                    .size(10)
                    .totalElements(2L)
                    .build();

            when(tenantManagementService.getAllTenants(anyInt(), anyInt(), any(), any())).thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/tenants")
                    .param("page", "0")
                    .param("size", "10")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.content", hasSize(2)))
                    .andExpect(jsonPath("$.data.totalElements").value(2));

            verify(tenantManagementService).getAllTenants(0, 10, null, null);
        }

        @Test
        void getAllTenants_EmptyList() throws Exception {
            PageResponseDTO<TenantResponseDTO> emptyPageResponse = PageResponseDTO.<TenantResponseDTO>builder()
                    .content(Collections.emptyList())
                    .number(0)
                    .size(10)
                    .totalElements(0L)
                    .build();

            when(tenantManagementService.getAllTenants(anyInt(), anyInt(), any(), any())).thenReturn(emptyPageResponse);

            mockMvc.perform(get("/api/v1/tenants")
                    .param("page", "0")
                    .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(0)));
        }

        @Test
        void getAllTenants_DefaultPagination() throws Exception {
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(1).name("Test").build();
            PageResponseDTO<TenantResponseDTO> pageResponse = PageResponseDTO.<TenantResponseDTO>builder()
                    .content(List.of(tenant))
                    .number(0)
                    .size(10)
                    .totalElements(1L)
                    .build();

            when(tenantManagementService.getAllTenants(0, 10, null, null)).thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/tenants"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)));
        }

        @Test
        void getAllTenants_FilterByStatus_ReturnsFilteredResults() throws Exception {
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(1).name("Active Tenant").status(TenantStatusEnum.ACTIVE.name()).build();
            PageResponseDTO<TenantResponseDTO> pageResponse = PageResponseDTO.<TenantResponseDTO>builder()
                    .content(List.of(tenant))
                    .number(0)
                    .size(10)
                    .totalElements(1L)
                    .build();

            when(tenantManagementService.getAllTenants(0, 10, TenantStatusEnum.ACTIVE, null)).thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/tenants")
                    .param("page", "0")
                    .param("size", "10")
                    .param("status", "ACTIVE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"));

            verify(tenantManagementService).getAllTenants(0, 10, TenantStatusEnum.ACTIVE, null);
        }

        @Test
        void getAllTenants_FilterBySearch_ReturnsFilteredResults() throws Exception {
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(1).name("Madhya Pradesh").build();
            PageResponseDTO<TenantResponseDTO> pageResponse = PageResponseDTO.<TenantResponseDTO>builder()
                    .content(List.of(tenant))
                    .number(0)
                    .size(10)
                    .totalElements(1L)
                    .build();

            when(tenantManagementService.getAllTenants(0, 10, null, "madhya")).thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/tenants")
                    .param("page", "0")
                    .param("size", "10")
                    .param("search", "madhya"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.content[0].name").value("Madhya Pradesh"));

            verify(tenantManagementService).getAllTenants(0, 10, null, "madhya");
        }

        @Test
        void getAllTenants_FilterByStatusAndSearch_ReturnsFilteredResults() throws Exception {
            TenantResponseDTO tenant = TenantResponseDTO.builder().id(1).name("Madhya Pradesh").status(TenantStatusEnum.ACTIVE.name()).build();
            PageResponseDTO<TenantResponseDTO> pageResponse = PageResponseDTO.<TenantResponseDTO>builder()
                    .content(List.of(tenant))
                    .number(0)
                    .size(10)
                    .totalElements(1L)
                    .build();

            when(tenantManagementService.getAllTenants(0, 10, TenantStatusEnum.ACTIVE, "madhya")).thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/tenants")
                    .param("status", "ACTIVE")
                    .param("search", "madhya"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)));

            verify(tenantManagementService).getAllTenants(0, 10, TenantStatusEnum.ACTIVE, "madhya");
        }

        @Test
        void getAllTenants_InvalidStatus_ReturnsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/tenants")
                    .param("status", "NOT_A_STATUS"))
                    .andExpect(status().isBadRequest());

            verify(tenantManagementService, never()).getAllTenants(anyInt(), anyInt(), any(), any());
        }
    }

    @Nested
    @DisplayName("Update Tenant")
    class UpdateTenantTests {
        @Test
        void updateTenant_Success() throws Exception {
            Integer tenantId = 1;
            UpdateTenantRequestDTO request = new UpdateTenantRequestDTO();
            request.setStatus(TenantStatusEnum.ACTIVE.name());

            TenantResponseDTO response = TenantResponseDTO.builder().id(1).status(TenantStatusEnum.ACTIVE.name()).build();

            when(tenantManagementService.updateTenant(eq(tenantId), any(UpdateTenantRequestDTO.class))).thenReturn(response);

            mockMvc.perform(put("/api/v1/tenants/" + tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.status").value(TenantStatusEnum.ACTIVE.name()));

            verify(tenantManagementService).updateTenant(eq(tenantId), any());
        }

        @Test
        void updateTenant_NotFound() throws Exception {
            Integer tenantId = 999;
            UpdateTenantRequestDTO request = UpdateTenantRequestDTO.builder().status(TenantStatusEnum.INACTIVE.name()).build();

            when(tenantManagementService.updateTenant(eq(tenantId), any()))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(put("/api/v1/tenants/" + tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Deactivate Tenant")
    class DeactivateTenantTests {
        @Test
        void deactivateTenant_Success() throws Exception {
            Integer tenantId = 1;
            doNothing().when(tenantManagementService).deactivateTenant(tenantId);

            mockMvc.perform(post("/api/v1/tenants/" + tenantId + "/deactivate")
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Tenant deactivated successfully"));

            verify(tenantManagementService).deactivateTenant(tenantId);
        }

        @Test
        void deactivateTenant_NotFound() throws Exception {
            Integer tenantId = 999;
            doThrow(new ResourceNotFoundException("Tenant not found"))
                    .when(tenantManagementService).deactivateTenant(tenantId);

            mockMvc.perform(post("/api/v1/tenants/" + tenantId + "/deactivate"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Get Tenant Summary")
    class GetTenantSummaryTests {

        @Test
        void getTenantSummary_Success() throws Exception {
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

            when(tenantManagementService.getTenantSummary()).thenReturn(summary);

            mockMvc.perform(get("/api/v1/tenants/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Tenant summary retrieved successfully"))
                    .andExpect(jsonPath("$.data.totalTenants").value(10))
                    .andExpect(jsonPath("$.data.onboardedTenants").value(2))
                    .andExpect(jsonPath("$.data.configuredTenants").value(1))
                    .andExpect(jsonPath("$.data.activeTenants").value(5))
                    .andExpect(jsonPath("$.data.inactiveTenants").value(1))
                    .andExpect(jsonPath("$.data.suspendedTenants").value(0))
                    .andExpect(jsonPath("$.data.degradedTenants").value(0))
                    .andExpect(jsonPath("$.data.archivedTenants").value(1));

            verify(tenantManagementService).getTenantSummary();
        }

        @Test
        void getTenantSummary_InternalError() throws Exception {
            when(tenantManagementService.getTenantSummary())
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/v1/tenants/summary"))
                    .andExpect(status().isInternalServerError());
        }
    }
}
