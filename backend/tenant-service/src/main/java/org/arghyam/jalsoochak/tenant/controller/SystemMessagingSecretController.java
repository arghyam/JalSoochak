package org.arghyam.jalsoochak.tenant.controller;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRewrapResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRotationResponseDTO;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
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
 * MESSAGING-PROVIDER-SECRETS: key rotation for the tenant messaging secret store.
 *
 * <p>SUPER_USER only, including the per-tenant rotation: rotating a key is an operational
 * response to a suspected exposure, not part of configuring a tenant, and it must not be
 * reachable by the state admin whose credentials may be the ones that leaked.
 */
@RestController
@RequestMapping("/api/v1/system/messaging-secrets")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Messaging Secret Administration",
        description = "Master-key and tenant data-key rotation. Super User only.")
@CommonApiResponses
public class SystemMessagingSecretController {

    private final TenantMessagingSecretService messagingSecretService;

    @Operation(summary = "Re-wrap all tenant data keys under the active master key",
            description = "Unwraps each tenant data key with the master key that wrapped it and re-wraps it with "
                    + "the active one. Secret ciphertexts are untouched, so this is one row per tenant. Run after "
                    + "adding a new master key and pointing messaging.secret.active-master-key-id at it; the "
                    + "previous master key can be removed from the environment once no key still names it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rewrap completed",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MessagingSecretRewrapResponseDTO.class))),
            @ApiResponse(responseCode = "503", description = "Secret storage is not configured for this deployment",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PreAuthorize("hasRole('SUPER_USER')")
    @PostMapping("/rewrap")
    public ResponseEntity<ApiResponseDTO<MessagingSecretRewrapResponseDTO>> rewrapDataKeys() {
        log.info("POST /api/v1/system/messaging-secrets/rewrap");
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant data keys re-wrapped successfully",
                messagingSecretService.rewrapDataKeys()));
    }

    @Operation(summary = "Rotate one tenant's data key",
            description = "Issues the tenant a new data key, re-encrypts its stored secrets under it and retires "
                    + "the previous version. For when a state's provider credentials are believed exposed; the "
                    + "credentials themselves still have to be changed at the provider and re-written.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Data key rotated",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MessagingSecretRotationResponseDTO.class))),
            @ApiResponse(responseCode = "404", description = "Tenant not found, or has no data key to rotate",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
            @ApiResponse(responseCode = "503", description = "Secret storage is not configured for this deployment",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PreAuthorize("hasRole('SUPER_USER')")
    @PostMapping("/tenants/{tenantId}/rotate")
    public ResponseEntity<ApiResponseDTO<MessagingSecretRotationResponseDTO>> rotateTenantDataKey(
            @PathVariable Integer tenantId) {
        log.info("POST /api/v1/system/messaging-secrets/tenants/{}/rotate", tenantId);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Tenant data key rotated successfully",
                messagingSecretService.rotateTenantDataKey(tenantId)));
    }
}
