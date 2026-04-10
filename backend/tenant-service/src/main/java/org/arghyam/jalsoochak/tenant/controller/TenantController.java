package org.arghyam.jalsoochak.tenant.controller;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LogoSource;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantLogoResult;
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
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Tenant Management", description = "APIs for tenant onboarding, schema provisioning, and tenant management")
@CommonApiResponses
public class TenantController {

        private static final int MAX_PAGE_SIZE = 100;

        private final TenantManagementService tenantManagementService;

        @Operation(summary = "Create a new tenant", description = "Registers a new tenant in the common schema and provisions a dedicated "
                        + "database schema (tenant_<stateCode>) with all required tables and indexes.")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "Tenant created and schema provisioned successfully"),
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
        @ApiResponse(responseCode = "200", description = "Tenant summary retrieved successfully")
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
                        @ApiResponse(responseCode = "200", description = "Paginated list of tenants"),
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
                        @ApiResponse(responseCode = "200", description = "Tenant updated successfully"),
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

        @Operation(summary = "Get the configurations for a tenant", description = "Retrieves either all or the selected configuration key-value pairs for a specific tenant in a Map format.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Tenant configurations retrieved successfully"),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @RequiresTenantAccess
        @GetMapping("/{tenantId}/config")
        public ResponseEntity<ApiResponseDTO<TenantConfigResponseDTO>> getTenantConfigs(
                        @PathVariable Integer tenantId,
                        @Parameter(description = "Optional set of configuration keys to retrieve. If not provided, all configurations are returned.", example = "KEY1, KEY2") @RequestParam(required = false) Set<TenantConfigKeyEnum> keys) {
                log.info("GET /api/v1/tenants/{}/config with keys: {}", tenantId, keys);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant configurations retrieved successfully",
                                tenantManagementService.getTenantConfigs(tenantId, keys)));
        }

        @Operation(summary = "Get public configurations for a tenant", description = "Returns only the configuration keys explicitly marked as public (isPublic=true). "
                        + "No authentication required. Suitable for use by public-facing dashboards.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Public tenant configurations retrieved successfully"),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @GetMapping("/{tenantId}/config/public")
        public ResponseEntity<ApiResponseDTO<TenantConfigResponseDTO>> getPublicTenantConfigs(
                        @PathVariable Integer tenantId) {
                log.info("GET /api/v1/tenants/{}/config/public", tenantId);
                Set<TenantConfigKeyEnum> publicKeys = Arrays.stream(TenantConfigKeyEnum.values())
                                .filter(TenantConfigKeyEnum::isPublic)
                                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TenantConfigKeyEnum.class)));
                if (publicKeys.isEmpty()) {
                        return ResponseEntity.ok(ApiResponseDTO.of(200, "Public tenant configurations retrieved successfully",
                                        TenantConfigResponseDTO.builder().tenantId(tenantId).configs(Collections.emptyMap()).build()));
                }
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Public tenant configurations retrieved successfully",
                                tenantManagementService.getTenantConfigs(tenantId, publicKeys)));
        }

        @Operation(summary = "Get configuration status for a tenant", description = "Returns the configuration completeness status for a tenant. "
                        + "Each known configuration key is listed with a CONFIGURED or PENDING status, "
                        + "along with an aggregate summary of total, configured, and pending counts.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Configuration status retrieved successfully"),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @RequiresTenantAccess
        @GetMapping("/{tenantId}/config/status")
        public ResponseEntity<ApiResponseDTO<TenantConfigStatusResponseDTO>> getTenantConfigStatus(
                        @PathVariable Integer tenantId) {
                log.info("GET /api/v1/tenants/{}/config/status", tenantId);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Configuration status retrieved successfully",
                                tenantManagementService.getTenantConfigStatus(tenantId)));
        }

        @Operation(summary = "Set or update multiple tenant configurations", description = "Batch updates or creates configurations for the specified tenant using a Map structure.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Configurations set successfully"),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @RequiresTenantAccess
        @PutMapping("/{tenantId}/config")
        public ResponseEntity<ApiResponseDTO<TenantConfigResponseDTO>> setTenantConfigs(
                        @PathVariable Integer tenantId,
                        @Valid @RequestBody SetTenantConfigRequestDTO request) {
                log.info("PUT /api/v1/tenants/{}/config mapping received", tenantId);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant configurations set successfully",
                                tenantManagementService.setTenantConfigs(tenantId, request)));
        }

        @Operation(summary = "Set tenant logo", description = "Sets the tenant logo from either a file upload or an external URL — exactly one must be provided. "
                        + "File (PNG, JPEG, WebP — max 2 MB): uploaded to internal object storage. "
                        + "URL (http/https): stored as a reference. "
                        + "In both cases, the previous managed object is deleted from storage if one existed.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Logo set and TENANT_LOGO config updated successfully"),
                        @ApiResponse(responseCode = "400", description = "Neither or both of file/url provided, unsupported MIME type, or invalid URL",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @RequiresTenantAccess
        @PutMapping(value = "/{tenantId}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        public ResponseEntity<ApiResponseDTO<TenantConfigResponseDTO>> setTenantLogo(
                        @PathVariable Integer tenantId,
                        @RequestParam(required = false) MultipartFile file,
                        @RequestParam(required = false) String url) {
                log.info("PUT /api/v1/tenants/{}/logo", tenantId);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "Logo set successfully",
                                tenantManagementService.setTenantLogo(tenantId, LogoSource.from(file, url))));
        }

        @Operation(summary = "Get tenant logo", description = "Proxies the tenant logo from internal object storage. "
                        + "For external logos (set via PUT /logo), responds with a 302 redirect to the external URL. "
                        + "Returns 404 if no logo has been configured for the tenant.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Logo image returned",
                                        content = @Content(mediaType = "image/*")),
                        @ApiResponse(responseCode = "302", description = "Redirect to external logo URL"),
                        @ApiResponse(responseCode = "404", description = "Tenant not found or logo not configured",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @GetMapping("/{tenantId}/logo")
        public ResponseEntity<StreamingResponseBody> getTenantLogo(@PathVariable Integer tenantId) {
                log.info("GET /api/v1/tenants/{}/logo", tenantId);
                return switch (tenantManagementService.resolveTenantLogo(tenantId)) {
                        case TenantLogoResult.Managed m -> ResponseEntity.ok()
                                        .contentType(MediaType.parseMediaType(m.contentType()))
                                        .body(out -> {
                                                try (var stream = m.stream()) {
                                                        stream.transferTo(out);
                                                }
                                        });
                        case TenantLogoResult.External e -> ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(e.redirectUrl()))
                                        .body(null);
                };
        }

        @Operation(summary = "Get location hierarchy configuration for a tenant", description = "Retrieves the location hierarchy structure (levels) for the specified hierarchy type (LGD or DEPARTMENT).")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Location hierarchy configuration retrieved successfully"),
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
                        @ApiResponse(responseCode = "200", description = "Edit constraints retrieved successfully"),
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
                        @ApiResponse(responseCode = "200", description = "Location hierarchy updated successfully"),
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
                        @ApiResponse(responseCode = "200", description = "Child locations retrieved successfully"),
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
