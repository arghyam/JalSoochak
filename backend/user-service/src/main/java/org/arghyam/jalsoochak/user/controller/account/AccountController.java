package org.arghyam.jalsoochak.user.controller.account;

import org.arghyam.jalsoochak.user.config.CommonApiResponses;
import org.arghyam.jalsoochak.user.dto.common.ApiErrorResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.ChangePasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateProfileRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.service.UserManagementService;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The authenticated user's own profile and password.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
@Tag(name = "Account", description = "The authenticated user's own profile and password")
@CommonApiResponses
public class AccountController {

    private final UserManagementService userManagementService;

    @Operation(summary = "Get own profile", description = "Retrieve the authenticated user's profile")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile retrieved"),
        @ApiResponse(responseCode = "404", description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponseDTO<AdminUserResponseDTO>> getMe(Authentication authentication) {
        log.info("GET /api/v1/users/me");
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Profile retrieved",
                userManagementService.getMe(SecurityUtils.getKeycloakId(authentication))));
    }

    @Operation(summary = "Update own profile", description = "Update first name, last name, or phone number of the authenticated user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile updated"),
        @ApiResponse(responseCode = "400", description = "Validation error or account deactivated",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PatchMapping("/me")
    public ResponseEntity<ApiResponseDTO<AdminUserResponseDTO>> updateMe(Authentication authentication,
                                                                         @Valid @RequestBody UpdateProfileRequestDTO request) {
        log.info("PATCH /api/v1/users/me");
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Profile updated",
                userManagementService.updateMe(SecurityUtils.getKeycloakId(authentication), request)));
    }

    @Operation(summary = "Change own password")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password changed"),
        @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class))),
        @ApiResponse(responseCode = "401", description = "Current password is incorrect",
            content = @Content(schema = @Schema(implementation = ApiErrorResponseDTO.class)))
    })
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponseDTO<Void>> changePassword(Authentication authentication,
                                                               @Valid @RequestBody ChangePasswordRequestDTO request) {
        log.info("PATCH /api/v1/users/me/password");
        userManagementService.changePassword(SecurityUtils.getKeycloakId(authentication), request);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Password changed successfully"));
    }
}
