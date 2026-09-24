package org.arghyam.jalsoochak.tenant.controller.tenant;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Onboards tenants and manages their lifecycle: creation, listing, status summary, update and
 * deactivation.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Tenant Lifecycle", description = "Tenant onboarding, schema provisioning, listing, update and deactivation")
@CommonApiResponses
public class TenantLifecycleController {

        private static final int MAX_PAGE_SIZE = 100;

        private final TenantManagementService tenantManagementService;

        @Operation(summary = "Create a new tenant", description = "Registers a new tenant in the common schema and provisions a dedicated "
                        + "database schema (tenant_<stateCode>) with all required tables and indexes.")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "Tenant created and schema provisioned successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid request — missing or malformed fields",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "409", description = "Tenant with the given state code already exists",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @PreAuthorize("hasRole('SUPER_USER')")
        @PostMapping
        public ResponseEntity<ApiResponseDTO<TenantResponseDTO>> createTenant(
                        @Valid @RequestBody CreateTenantRequestDTO request) {
                log.info("POST /api/v1/tenants – Creating tenant: {}", request.getName());
                TenantResponseDTO tenant = tenantManagementService.createTenant(request);
                return ResponseEntity.status(HttpStatus.CREATED)
                                .body(ApiResponseDTO.of(201, "Tenant created successfully", tenant));
        }

        @Operation(summary = "Get all tenant's status summary", description = "Returns aggregate counts of all non-system tenants grouped by status: total, onboarded, configured, active, inactive, suspended, degraded, and archived.")
        @ApiResponse(responseCode = "200", description = "Tenant summary retrieved successfully",
                        content = @Content(mediaType = "application/json",
                                        schema = @Schema(implementation = TenantSummaryResponseDTO.class)))
        @PreAuthorize("hasRole('SUPER_USER')")
        @GetMapping("/summary")
        public ResponseEntity<ApiResponseDTO<TenantSummaryResponseDTO>> getTenantSummary() {
                log.info("GET /api/v1/tenants/summary");
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant summary retrieved successfully",
                                tenantManagementService.getTenantSummary()));
        }

        @Operation(summary = "List all tenants with pagination", description = "Returns a paginated list of tenants registered in the common schema, ordered by ID. "
                        + "Optionally filter by status and/or search by name (case-insensitive partial match).")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Paginated list of tenants",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = PageResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Invalid query parameter",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @GetMapping
        public ResponseEntity<ApiResponseDTO<PageResponseDTO<TenantResponseDTO>>> getAllTenants(
                        @Parameter(description = "Page number (0-indexed)", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
                        @Parameter(description = "Page size", example = "10") @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_PAGE_SIZE) int size,
                        @Parameter(description = "Filter by tenant status", example = "ACTIVE") @RequestParam(required = false) TenantStatusEnum status,
                        @Parameter(description = "Case-insensitive partial match on tenant name", example = "madhya") @RequestParam(required = false) String search) {
                log.info("GET /api/v1/tenants – page: {}, size: {}, status: {}, search: {}", page, size, status, search);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenants retrieved successfully",
                                tenantManagementService.getAllTenants(page, size, status, search)));
        }

        @Operation(summary = "Update tenant", description = "Updates the status of an existing tenant identified by tenantId.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Tenant updated successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantResponseDTO.class))),
                        @ApiResponse(responseCode = "400", description = "Tenant updation failed",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Tenant with given tenantId does not exist",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @PreAuthorize("hasRole('SUPER_USER')")
        @PutMapping("/{tenantId}")
        public ResponseEntity<ApiResponseDTO<TenantResponseDTO>> updateTenant(
                        @PathVariable Integer tenantId,
                        @Valid @RequestBody UpdateTenantRequestDTO request) {
                log.info("PUT /api/v1/tenants/{}", tenantId);
                TenantResponseDTO updated = tenantManagementService.updateTenant(tenantId, request);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant updated successfully", updated));
        }

        @Operation(summary = "Deactivate a tenant", description = "Sets the tenant status to INACTIVE for the given tenantId.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Tenant deactivated successfully"),
                        @ApiResponse(responseCode = "400", description = "Tenant deactivation failed",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Tenant with given tenantId does not exist",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @PreAuthorize("hasRole('SUPER_USER')")
        @PostMapping("/{tenantId}/deactivate")
        public ResponseEntity<ApiResponseDTO<Void>> deactivateTenant(@PathVariable Integer tenantId) {
                log.info("POST /api/v1/tenants/{}/deactivate", tenantId);
                tenantManagementService.deactivateTenant(tenantId);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant deactivated successfully"));
        }

}
