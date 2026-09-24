package org.arghyam.jalsoochak.tenant.controller.tenant;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.springframework.http.ResponseEntity;
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
 * A tenant's configuration values, the subset published as public, and how complete they are.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tenant Configuration", description = "Per-tenant configuration values and their completeness")
@CommonApiResponses
public class TenantConfigController {

        private final TenantManagementService tenantManagementService;

        @Operation(summary = "Get the configurations for a tenant", description = "Retrieves either all or the selected configuration key-value pairs for a specific tenant in a Map format.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Tenant configurations retrieved successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantConfigResponseDTO.class))),
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
                        @ApiResponse(responseCode = "200", description = "Public tenant configurations retrieved successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantConfigResponseDTO.class))),
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
                        @ApiResponse(responseCode = "200", description = "Configuration status retrieved successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantConfigStatusResponseDTO.class))),
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
                        @ApiResponse(responseCode = "200", description = "Configurations set successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantConfigResponseDTO.class))),
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

}
