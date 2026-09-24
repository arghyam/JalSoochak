package org.arghyam.jalsoochak.tenant.controller.tenant;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.GenerateApiTokenResponseDTO;
import org.arghyam.jalsoochak.tenant.exception.ForbiddenAccessException;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues the State-IT integration token for the calling STATE_ADMIN's own tenant.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tenant API Token", description = "State-IT integration token for the caller's tenant")
@CommonApiResponses
public class TenantApiTokenController {

        private final TenantManagementService tenantManagementService;

        @Operation(summary = "Generate API token for the caller's tenant",
                        description = "Generates a new API token (create-or-replace) for the STATE_ADMIN's own tenant. "
                                        + "Tenant is resolved from the caller's JWT — no tenant ID required. "
                                        + "The raw token is returned exactly once and is never stored — save it immediately. "
                                        + "Calling this again invalidates the previous token.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "API token generated successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = GenerateApiTokenResponseDTO.class))),
                        @ApiResponse(responseCode = "403", description = "Caller is not a STATE_ADMIN or JWT is missing tenant_state_code claim",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
                        @ApiResponse(responseCode = "404", description = "Tenant not found",
                                        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
        })
        @PreAuthorize("hasRole('STATE_ADMIN')")
        @PostMapping("/api-token")
        public ResponseEntity<ApiResponseDTO<GenerateApiTokenResponseDTO>> generateApiToken() {
                String stateCode = SecurityUtils.getCurrentUserTenantStateCode();
                if (stateCode == null || stateCode.isBlank()) {
                        throw new ForbiddenAccessException(
                                        "No tenant associated with this account");
                }
                log.info("POST /api/v1/tenants/api-token [stateCode={}]", stateCode);
                return ResponseEntity.ok(ApiResponseDTO.of(200, "API token generated successfully",
                                tenantManagementService.generateApiToken(stateCode)));
        }

}
