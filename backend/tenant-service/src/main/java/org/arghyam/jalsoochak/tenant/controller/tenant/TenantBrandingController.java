package org.arghyam.jalsoochak.tenant.controller.tenant;

import java.net.URI;

import org.arghyam.jalsoochak.tenant.config.CommonApiResponses;
import org.arghyam.jalsoochak.tenant.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LogoSource;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantLogoResult;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A tenant's logo, set from an upload or an external URL and served back from object storage.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tenant Branding", description = "Tenant logo upload and retrieval")
@CommonApiResponses
public class TenantBrandingController {

        private final TenantManagementService tenantManagementService;

        @Operation(summary = "Set tenant logo", description = "Sets the tenant logo from either a file upload or an external URL — exactly one must be provided. "
                        + "File (PNG, JPEG, WebP — max 2 MB): uploaded to internal object storage. "
                        + "URL (http/https): stored as a reference. "
                        + "In both cases, the previous managed object is deleted from storage if one existed.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Logo set and TENANT_LOGO config updated successfully",
                                        content = @Content(mediaType = "application/json",
                                                        schema = @Schema(implementation = TenantConfigResponseDTO.class))),
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

}
