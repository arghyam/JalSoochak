package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelNameDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.StatusEnum;
import org.arghyam.jalsoochak.tenant.exception.LocationHierarchyStructureLockedException;
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

@WebMvcTest(TenantLocationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Tenant Location Controller Tests")
class TenantLocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Location Hierarchy")
    class LocationHierarchyTests {
        @Test
        void getLocationHierarchy_Success() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";

            List<LocationLevelConfigDTO> levels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );

            LocationHierarchyResponseDTO hierarchy = LocationHierarchyResponseDTO.builder()
                    .hierarchyType(hierarchyType)
                    .levels(levels)
                    .build();

            when(tenantManagementService.getLocationHierarchy(tenantId, hierarchyType)).thenReturn(hierarchy);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}", tenantId, hierarchyType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.hierarchyType").value("LGD"))
                    .andExpect(jsonPath("$.data.levels", hasSize(2)))
                    .andExpect(jsonPath("$.data.levels[0].levelName[0].title").value("State"))
                    .andExpect(jsonPath("$.data.levels[1].levelName[0].title").value("District"));

            verify(tenantManagementService).getLocationHierarchy(tenantId, hierarchyType);
        }

        @Test
        void getLocationHierarchy_DepartmentType_Success() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "DEPARTMENT";

            List<LocationLevelConfigDTO> levels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Zone").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Circle").build())).build(),
                    LocationLevelConfigDTO.builder().level(3)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Division").build())).build()
            );

            LocationHierarchyResponseDTO hierarchy = LocationHierarchyResponseDTO.builder()
                    .hierarchyType(hierarchyType)
                    .levels(levels)
                    .build();

            when(tenantManagementService.getLocationHierarchy(tenantId, hierarchyType)).thenReturn(hierarchy);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}", tenantId, hierarchyType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hierarchyType").value("DEPARTMENT"))
                    .andExpect(jsonPath("$.data.levels", hasSize(3)));
        }

        @Test
        void getLocationHierarchy_InvalidType_ReturnsBadRequest() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "INVALID_TYPE";

            when(tenantManagementService.getLocationHierarchy(tenantId, hierarchyType))
                    .thenThrow(new IllegalArgumentException("Invalid hierarchy type: INVALID_TYPE"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}", tenantId, hierarchyType))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void getLocationHierarchy_ResourceNotFound() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";

            when(tenantManagementService.getLocationHierarchy(tenantId, hierarchyType))
                    .thenThrow(new ResourceNotFoundException("Hierarchy not found"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}", tenantId, hierarchyType))
                    .andExpect(status().isNotFound());

            verify(tenantManagementService).getLocationHierarchy(tenantId, hierarchyType);
        }

        @Test
        void getLocationChildren_Success() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";

            List<LocationResponseDTO> children = List.of(
                    LocationResponseDTO.builder().id(1).uuid("uuid-1").title("Madhya Pradesh").status(StatusEnum.ACTIVE.getCode()).build(),
                    LocationResponseDTO.builder().id(2).uuid("uuid-2").title("Maharashtra").status(StatusEnum.ACTIVE.getCode()).build()
            );

            when(tenantManagementService.getLocationChildren(tenantId, hierarchyType, null)).thenReturn(children);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/locations/{hierarchyType}", tenantId, hierarchyType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].title").value("Madhya Pradesh"))
                    .andExpect(jsonPath("$.data[1].title").value("Maharashtra"));

            verify(tenantManagementService).getLocationChildren(tenantId, hierarchyType, null);
        }

        @Test
        void getLocationChildren_WithParentId() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";
            Integer parentId = 1;

            List<LocationResponseDTO> children = List.of(
                    LocationResponseDTO.builder().id(10).uuid("uuid-10").title("Indore").lgdCode("IND001").parentId(1).status(StatusEnum.ACTIVE.getCode()).build(),
                    LocationResponseDTO.builder().id(11).uuid("uuid-11").title("Bhopal").lgdCode("BHP001").parentId(1).status(StatusEnum.ACTIVE.getCode()).build()
            );

            when(tenantManagementService.getLocationChildren(tenantId, hierarchyType, parentId)).thenReturn(children);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/locations/{hierarchyType}", tenantId, hierarchyType)
                            .param("parentId", parentId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].title").value("Indore"))
                    .andExpect(jsonPath("$.data[0].parentId").value(1));

            verify(tenantManagementService).getLocationChildren(tenantId, hierarchyType, parentId);
        }

        @Test
        void getLocationChildren_EmptyResult() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "DEPARTMENT";
            Integer parentId = 999;

            when(tenantManagementService.getLocationChildren(tenantId, hierarchyType, parentId)).thenReturn(new ArrayList<>());

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/locations/{hierarchyType}", tenantId, hierarchyType)
                            .param("parentId", parentId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data", hasSize(0)));

            verify(tenantManagementService).getLocationChildren(tenantId, hierarchyType, parentId);
        }

        @Test
        void getLocationChildren_InvalidHierarchyType() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "INVALID";

            when(tenantManagementService.getLocationChildren(tenantId, hierarchyType, null))
                    .thenThrow(new IllegalArgumentException("Invalid hierarchy type: " + hierarchyType));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/locations/{hierarchyType}", tenantId, hierarchyType))
                    .andExpect(status().isBadRequest());

            verify(tenantManagementService).getLocationChildren(tenantId, hierarchyType, null);
        }

        @Test
        void getLocationHierarchyEditConstraints_StructuralAllowed() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";

            LocationHierarchyEditConstraintsResponseDTO constraints =
                    LocationHierarchyEditConstraintsResponseDTO.builder()
                            .hierarchyType("LGD")
                            .structuralChangesAllowed(true)
                            .seededRecordCount(0L)
                            .build();

            when(tenantManagementService.getLocationHierarchyEditConstraints(tenantId, hierarchyType))
                    .thenReturn(constraints);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints",
                            tenantId, hierarchyType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.hierarchyType").value("LGD"))
                    .andExpect(jsonPath("$.data.structuralChangesAllowed").value(true))
                    .andExpect(jsonPath("$.data.seededRecordCount").value(0));

            verify(tenantManagementService).getLocationHierarchyEditConstraints(tenantId, hierarchyType);
        }

        @Test
        void getLocationHierarchyEditConstraints_StructuralLocked() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "DEPARTMENT";

            LocationHierarchyEditConstraintsResponseDTO constraints =
                    LocationHierarchyEditConstraintsResponseDTO.builder()
                            .hierarchyType("DEPARTMENT")
                            .structuralChangesAllowed(false)
                            .seededRecordCount(512L)
                            .build();

            when(tenantManagementService.getLocationHierarchyEditConstraints(tenantId, hierarchyType))
                    .thenReturn(constraints);

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints",
                            tenantId, hierarchyType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.structuralChangesAllowed").value(false))
                    .andExpect(jsonPath("$.data.seededRecordCount").value(512));
        }

        @Test
        void getLocationHierarchyEditConstraints_TenantNotFound() throws Exception {
            Integer tenantId = 999;
            when(tenantManagementService.getLocationHierarchyEditConstraints(tenantId, "LGD"))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints",
                            tenantId, "LGD"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void getLocationHierarchyEditConstraints_InvalidType() throws Exception {
            Integer tenantId = 1;
            when(tenantManagementService.getLocationHierarchyEditConstraints(tenantId, "INVALID"))
                    .thenThrow(new IllegalArgumentException("Invalid hierarchy type: INVALID"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints",
                            tenantId, "INVALID"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void updateLocationHierarchy_Success() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";

            List<LocationLevelConfigDTO> levels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Rajya").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("Zila").build())).build()
            );

            LocationHierarchyResponseDTO response = LocationHierarchyResponseDTO.builder()
                    .hierarchyType("LGD")
                    .levels(levels)
                    .build();

            when(tenantManagementService.updateLocationHierarchy(eq(tenantId), eq(hierarchyType), any()))
                    .thenReturn(response);

            mockMvc.perform(put("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}",
                            tenantId, hierarchyType)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(levels)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.data.hierarchyType").value("LGD"))
                    .andExpect(jsonPath("$.data.levels", hasSize(2)));

            verify(tenantManagementService).updateLocationHierarchy(eq(tenantId), eq(hierarchyType), any());
        }

        @Test
        void updateLocationHierarchy_StructureLocked_Returns409() throws Exception {
            Integer tenantId = 1;
            String hierarchyType = "LGD";

            List<LocationLevelConfigDTO> levels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build(),
                    LocationLevelConfigDTO.builder().level(2)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("District").build())).build()
            );

            when(tenantManagementService.updateLocationHierarchy(eq(tenantId), eq(hierarchyType), any()))
                    .thenThrow(new LocationHierarchyStructureLockedException("LGD", 1842L));

            mockMvc.perform(put("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}",
                            tenantId, hierarchyType)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(levels)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        void updateLocationHierarchy_TenantNotFound() throws Exception {
            Integer tenantId = 999;
            List<LocationLevelConfigDTO> levels = List.of(
                    LocationLevelConfigDTO.builder().level(1)
                            .levelName(List.of(LocationLevelNameDTO.builder().title("State").build())).build()
            );

            when(tenantManagementService.updateLocationHierarchy(eq(tenantId), eq("LGD"), any()))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(put("/api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}",
                            tenantId, "LGD")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(levels)))
                    .andExpect(status().isNotFound());
        }
    }
}
