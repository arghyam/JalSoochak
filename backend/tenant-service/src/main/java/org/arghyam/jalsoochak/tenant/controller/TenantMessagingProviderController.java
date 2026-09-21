package org.arghyam.jalsoochak.tenant.controller;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSecretsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * MESSAGING-PROVIDER-SECRETS: a tenant's own email and SMS provider configuration.
 *
 * <p>{@code @RequiresTenantAccess} on every endpoint: a SUPER_USER may configure any
 * tenant, a STATE_ADMIN only the tenant matching their {@code tenant_state_code} claim.
 * States own their provider accounts, so a state admin sets their own credentials without
 * ops involvement — but cannot read or change another state's.
 *
 * <p>Secrets are write-only. A {@code GET} answers SET or MISSING per secret name; there is
 * no response shape anywhere that carries a value back out.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/messaging-providers")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tenant Messaging Providers",
        description = "Per-tenant email and SMS provider credentials")
@CommonApiResponses
public class TenantMessagingProviderController {

    private final TenantMessagingSecretService messagingSecretService;

    @Operation(summary = "Store provider secrets for a channel",
            description = "Encrypts and stores the named credentials for the tenant's own provider account. "
                    + "Only the names present are written; a name left out keeps its current value. "
                    + "Returns the resulting SET/MISSING status, never a value.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Secrets stored successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MessagingProviderSecretStatusResponseDTO.class))),
            @ApiResponse(responseCode = "400", description = "Unknown secret name for the channel, or a blank value",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
            @ApiResponse(responseCode = "503", description = "Secret storage is not configured for this deployment",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @RequiresTenantAccess
    @PutMapping("/{channel}/secrets")
    public ResponseEntity<ApiResponseDTO<MessagingProviderSecretStatusResponseDTO>> setSecrets(
            @PathVariable Integer tenantId,
            @Parameter(description = "Messaging channel", example = "SMS") @PathVariable MessagingChannel channel,
            @Valid @RequestBody SetMessagingProviderSecretsRequestDTO request) {
        // Names only. A request body with credentials in it must never be logged.
        log.info("PUT /api/v1/tenants/{}/messaging-providers/{}/secrets [secretNames={}]",
                tenantId, channel, request.getSecrets().keySet());
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Messaging provider secrets stored successfully",
                messagingSecretService.setSecrets(tenantId, channel, request)));
    }

    @Operation(summary = "Get provider secret status for a channel",
            description = "Reports SET or MISSING for each secret the channel supports. Values are never returned.")
    @ApiResponse(responseCode = "200", description = "Secret status retrieved successfully",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MessagingProviderSecretStatusResponseDTO.class)))
    @RequiresTenantAccess
    @GetMapping("/{channel}/secrets")
    public ResponseEntity<ApiResponseDTO<MessagingProviderSecretStatusResponseDTO>> getSecretStatus(
            @PathVariable Integer tenantId,
            @Parameter(description = "Messaging channel", example = "SMS") @PathVariable MessagingChannel channel) {
        log.info("GET /api/v1/tenants/{}/messaging-providers/{}/secrets", tenantId, channel);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Messaging provider secret status retrieved successfully",
                messagingSecretService.getSecretStatus(tenantId, channel)));
    }

    @Operation(summary = "Delete all provider secrets for a channel",
            description = "Soft-deletes every stored credential on the channel, so the tenant falls back to the "
                    + "system default provider. Replace a single rotated credential with PUT instead.")
    @ApiResponse(responseCode = "200", description = "Secrets deleted successfully",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MessagingProviderSecretStatusResponseDTO.class)))
    @RequiresTenantAccess
    @DeleteMapping("/{channel}/secrets")
    public ResponseEntity<ApiResponseDTO<MessagingProviderSecretStatusResponseDTO>> deleteSecrets(
            @PathVariable Integer tenantId,
            @Parameter(description = "Messaging channel", example = "SMS") @PathVariable MessagingChannel channel) {
        log.info("DELETE /api/v1/tenants/{}/messaging-providers/{}/secrets", tenantId, channel);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Messaging provider secrets deleted successfully",
                messagingSecretService.deleteSecrets(tenantId, channel)));
    }
}
