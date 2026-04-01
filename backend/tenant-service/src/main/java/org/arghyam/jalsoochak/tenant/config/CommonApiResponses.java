package org.arghyam.jalsoochak.tenant.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.arghyam.jalsoochak.tenant.dto.common.ApiErrorResponseDTO;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Meta-annotation that documents the three error responses common to every
 * authenticated endpoint: 401 Unauthorized, 403 Forbidden, and 500 Internal
 * Server Error. Apply at the controller class level to avoid repeating these
 * on every method.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Unauthorized — valid Bearer token required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
    @ApiResponse(responseCode = "403", description = "Forbidden — insufficient role or scope",
        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
    @ApiResponse(responseCode = "500", description = "Internal server error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
})
public @interface CommonApiResponses {}
