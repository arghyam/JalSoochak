package org.arghyam.jalsoochak.tenant.controller;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSecretsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSettingsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingProviderService;
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
 * MESSAGING-PROVIDER-SETTINGS / SECRETS: a tenant's own email and SMS provider configuration.
 *
 * <p>Two halves behind one path. The collection endpoints carry the settings — which provider,
 * which from address, which template and DLT ids — and the {@code /{channel}/secrets} endpoints
 * carry the credentials those settings need. They are stored separately (an ordinary config row
 * against encrypted rows under a per-tenant data key) but neither is usable alone, so the
 * collection {@code GET} reports both and says whether the tenant's provider will actually be used.
 *
 * <p>{@code @RequiresTenantAccess} on every endpoint: a SUPER_USER may configure any
 * tenant, a STATE_ADMIN only the tenant matching their {@code tenant_state_code} claim.
 * States own their provider accounts, so a state admin sets their own credentials without
 * ops involvement — but cannot read or change another state's.
 *
 * <p>Secrets are write-only. A {@code GET} answers SET or MISSING per secret name; there is
 * no response shape anywhere that carries a value back out. Settings deliberately hold no
 * credential and no reference to one — the server derives each secret's location from the tenant,
 * the channel and the name (O2-8).
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
    private final TenantMessagingProviderService messagingProviderService;

    // ── settings ────────────────────────────────────────────────────────────────

    @Operation(summary = "Set provider settings for a tenant",
            description = "Stores the tenant's email and/or SMS provider settings. A channel left out of "
                    + "the body keeps its current settings. Credentials are not accepted here \u2014 store them "
                    + "with PUT /{channel}/secrets.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings stored successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = MessagingProviderConfigResponseDTO.class))),
            @ApiResponse(responseCode = "400",
                    description = "Unsupported provider, missing required field, an SMTP host that is not "
                            + "allowlisted, not TLS or not publicly resolvable, or an invalid OTP template",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @RequiresTenantAccess
    @PutMapping
    public ResponseEntity<ApiResponseDTO<MessagingProviderConfigResponseDTO>> setProviderSettings(
            @PathVariable Integer tenantId,
            @Valid @RequestBody SetMessagingProviderSettingsRequestDTO request) {
        log.info("PUT /api/v1/tenants/{}/messaging-providers [email={}, sms={}]",
                tenantId, request.getEmail() != null, request.getSms() != null);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Messaging provider settings stored successfully",
                messagingProviderService.setProviderSettings(tenantId, request)));
    }

    @Operation(summary = "Get provider settings and secret status for a tenant",
            description = "Returns both channels' settings together with SET/MISSING per credential. "
                    + "Never returns a secret value.")
    @ApiResponse(responseCode = "200", description = "Provider configuration retrieved successfully",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MessagingProviderConfigResponseDTO.class)))
    @RequiresTenantAccess
    @GetMapping
    public ResponseEntity<ApiResponseDTO<MessagingProviderConfigResponseDTO>> getProviderConfig(
            @PathVariable Integer tenantId) {
        log.info("GET /api/v1/tenants/{}/messaging-providers", tenantId);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Messaging provider configuration retrieved successfully",
                messagingProviderService.getProviderConfig(tenantId)));
    }

    @Operation(summary = "Delete provider settings for a channel",
            description = "Removes the channel's settings, so the tenant falls back to the system default "
                    + "provider. Stored credentials are left alone; delete those separately.")
    @ApiResponse(responseCode = "200", description = "Settings deleted successfully",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MessagingProviderConfigResponseDTO.class)))
    @RequiresTenantAccess
    @DeleteMapping("/{channel}")
    public ResponseEntity<ApiResponseDTO<MessagingProviderConfigResponseDTO>> deleteProviderSettings(
            @PathVariable Integer tenantId,
            @Parameter(description = "Messaging channel", example = "EMAIL") @PathVariable MessagingChannel channel) {
        log.info("DELETE /api/v1/tenants/{}/messaging-providers/{}", tenantId, channel);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Messaging provider settings deleted successfully",
                messagingProviderService.deleteProviderSettings(tenantId, channel)));
    }

    // ── secrets ─────────────────────────────────────────────────────────────────

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
