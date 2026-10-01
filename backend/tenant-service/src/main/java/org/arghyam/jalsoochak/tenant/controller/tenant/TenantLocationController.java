package org.arghyam.jalsoochak.tenant.controller.tenant;

import java.util.List;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A tenant's location hierarchies, the constraints on editing them, and the locations within them.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Tenant Locations", description = "Location hierarchy configuration and location lookup")
@CommonApiResponses
public class TenantLocationController {

        private final TenantManagementService tenantManagementService;

        @Operation(summary = "Get location hierarchy configuration for a tenant", description = "Retrieves the location hierarchy structure (levels) for the specified hierarchy type (LGD or DEPARTMENT).")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Location hierarchy configuration retrieved successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = LocationHierarchyResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid hierarchy type or tenant could not be resolved",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Hierarchy configuration not found for the tenant",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @GetMapping("/{tenantId}/location-hierarchy/{hierarchyType}")
        public ResponseEntity<ApiResponseDTO<LocationHierarchyResponseDTO>> getTenantLocationHierarchy(
                        @PathVariable Integer tenantId,
                        @Parameter(description = "Hierarchy type: LGD or DEPARTMENT", example = "LGD") @PathVariable String hierarchyType) {
                log.info("GET /api/v1/tenants/{}/location-hierarchy/{}", tenantId, hierarchyType);
                LocationHierarchyResponseDTO hierarchy = tenantManagementService.getLocationHierarchy(tenantId,
                                hierarchyType);
                return ResponseEntity
                                .ok(ApiResponseDTO.of(200, "Location hierarchy retrieved successfully", hierarchy));
        }

        @Operation(summary = "Get location hierarchy edit constraints", description = "Returns whether structural changes (add/remove levels) are permitted for the given hierarchy type. "
                        + "Structural changes are blocked when seeded location data exists in the master table.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Edit constraints retrieved successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = LocationHierarchyEditConstraintsResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid hierarchy type",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @RequiresTenantAccess
        @GetMapping("/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints")
        public ResponseEntity<ApiResponseDTO<LocationHierarchyEditConstraintsResponseDTO>> getLocationHierarchyEditConstraints(
                        @PathVariable Integer tenantId,
                        @Parameter(description = "Hierarchy type: LGD or DEPARTMENT", example = "LGD") @PathVariable String hierarchyType) {
                log.info("GET /api/v1/tenants/{}/location-hierarchy/{}/edit-constraints", tenantId, hierarchyType);
                LocationHierarchyEditConstraintsResponseDTO constraints = tenantManagementService
                                .getLocationHierarchyEditConstraints(tenantId, hierarchyType);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Edit constraints retrieved successfully", constraints));
        }

        @Operation(summary = "Update location hierarchy for a tenant", description = "Updates the location hierarchy levels. "
                        + "If no seeded data exists, full structural changes (add/remove levels) are allowed. "
                        + "If seeded data exists, only level name changes are permitted; structural changes return 409.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Location hierarchy updated successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = LocationHierarchyResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid hierarchy type or empty levels",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "409", description = "Structural change blocked — seeded data exists",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @RequiresTenantAccess
        @PutMapping("/{tenantId}/location-hierarchy/{hierarchyType}")
        public ResponseEntity<ApiResponseDTO<LocationHierarchyResponseDTO>> updateLocationHierarchy(
                        @PathVariable Integer tenantId,
                        @Parameter(description = "Hierarchy type: LGD or DEPARTMENT", example = "LGD") @PathVariable String hierarchyType,
                        @RequestBody @Valid List<LocationLevelConfigDTO> levels) {
                log.info("PUT /api/v1/tenants/{}/location-hierarchy/{}", tenantId, hierarchyType);
                LocationHierarchyResponseDTO updated = tenantManagementService.updateLocationHierarchy(tenantId,
                                hierarchyType, levels);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Location hierarchy updated successfully", updated));
        }

        @Operation(summary = "Get child locations by parent ID", description = "Fetches all child locations under the specified parent location in the given hierarchy type. "
                        + "Omit parentId to fetch root-level locations (where parent_id IS NULL).")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Child locations retrieved successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = LocationResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid hierarchy type or tenant could not be resolved",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @GetMapping("/{tenantId}/locations/{hierarchyType}")
        public ResponseEntity<ApiResponseDTO<List<LocationResponseDTO>>> getLocationChildren(
                        @PathVariable Integer tenantId,
                        @Parameter(description = "Hierarchy type: LGD or DEPARTMENT", example = "LGD") @PathVariable String hierarchyType,
                        @Parameter(description = "Parent location ID (omit for root-level locations)", example = "1") @RequestParam(required = false) Integer parentId) {
                log.info("GET /api/v1/tenants/{}/locations/{} parentId={}", tenantId, hierarchyType, parentId);
                List<LocationResponseDTO> children = tenantManagementService.getLocationChildren(tenantId,
                                hierarchyType, parentId);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Child locations retrieved successfully", children));
        }

}
