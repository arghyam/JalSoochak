package org.arghyam.jalsoochak.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.ChangePasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.InviteRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateProfileRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.service.UserManagementService;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
@Tag(name = "User Management", description = "Admin user lifecycle: invite, profile management, activation and deactivation")
public class UserController {

    private final UserManagementService userManagementService;

    @Operation(summary = "Invite a user", description = "Send an email invitation to a new SUPER_USER or STATE_ADMIN")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Invitation sent successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request or pending invite with conflicting role/tenant"),
        @ApiResponse(responseCode = "403", description = "Caller lacks permission to invite this role or tenant"),
        @ApiResponse(responseCode = "404", description = "Tenant not found for the given state code"),
        @ApiResponse(responseCode = "409", description = "User with this email already exists")
    })
    @PostMapping("/invite")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<Void>> inviteUser(@Valid @RequestBody InviteRequestDTO request,
                                                           Authentication authentication) {
        log.info("POST /api/v1/users/invite – role={}", request.getRole());
        log.debug("POST /api/v1/users/invite – tenantCode={}", request.getTenantCode());
        userManagementService.inviteUser(request, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Invitation sent successfully"));
    }

    @Operation(summary = "Get own profile", description = "Retrieve the authenticated user's profile")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Profile retrieved"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
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
        @ApiResponse(responseCode = "400", description = "Validation error or account deactivated"),
        @ApiResponse(responseCode = "401", description = "Not authenticated")
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
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Current password is incorrect")
    })
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponseDTO<Void>> changePassword(Authentication authentication,
                                                               @Valid @RequestBody ChangePasswordRequestDTO request) {
        log.info("PATCH /api/v1/users/me/password");
        userManagementService.changePassword(SecurityUtils.getKeycloakId(authentication), request);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Password changed successfully"));
    }

    @Operation(summary = "List super users", description = "Paginated list of all SUPER_USER accounts")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Super users retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid status value"),
        @ApiResponse(responseCode = "403", description = "Only SUPER_USER role allowed")
    })
    @GetMapping("/super-users")
    @PreAuthorize("hasRole('SUPER_USER')")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<AdminUserResponseDTO>>> listSuperUsers(
            @Parameter(description = "Filter by account status (ACTIVE, INACTIVE, PENDING). When null, no status filter is applied.")
                @RequestParam(required = false) AdminUserStatus status,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        log.info("GET /api/v1/users/super-users – status={}, page={}, size={}", status, page, size);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Super users retrieved",
                userManagementService.listSuperUsers(status, page, size)));
    }

    @Operation(summary = "List state admins",
            description = "Paginated list of STATE_ADMIN accounts, optionally filtered by tenant. STATE_ADMIN callers are automatically scoped to their own state. Name search is exact and case-insensitive; applies to all statuses.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "State admins retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid status value"),
        @ApiResponse(responseCode = "403", description = "STATE_ADMIN attempted to filter outside their state"),
        @ApiResponse(responseCode = "404", description = "Tenant not found for given tenantCode")
    })
    @GetMapping("/state-admins")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<AdminUserResponseDTO>>> listStateAdmins(
            @Parameter(description = "Tenant state code filter (optional for SUPER_USER)") @RequestParam(required = false) String tenantCode,
            @Parameter(description = "Filter by account status (ACTIVE, INACTIVE, PENDING). When null, no status filter is applied.")
                @RequestParam(required = false) AdminUserStatus status,
            @Parameter(description = "Exact full-name filter (case-insensitive). Applies to all statuses.")
                @RequestParam(required = false) String name,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        log.info("GET /api/v1/users/state-admins – status={}, page={}, size={}", status, page, size);
        log.debug("GET /api/v1/users/state-admins – tenantCode={}, name={}", tenantCode, name);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "State admins retrieved",
                userManagementService.listStateAdmins(tenantCode, status, name, authentication, page, size)));
    }

    @Operation(summary = "Get user by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User retrieved"),
        @ApiResponse(responseCode = "403", description = "STATE_ADMIN attempted cross-state access"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<AdminUserResponseDTO>> getUserById(
            @Parameter(description = "User ID") @PathVariable Long id,
            Authentication authentication) {
        log.info("GET /api/v1/users/[id]");
        log.debug("GET /api/v1/users/{}", id);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "User retrieved", userManagementService.getUserById(id, authentication)));
    }

    @Operation(summary = "Update user by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User updated"),
        @ApiResponse(responseCode = "400", description = "User is PENDING or INACTIVE"),
        @ApiResponse(responseCode = "403", description = "STATE_ADMIN attempted cross-state update"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<AdminUserResponseDTO>> updateUserById(
            @Parameter(description = "User ID") @PathVariable Long id,
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequestDTO request) {
        log.info("PATCH /api/v1/users/[id]");
        log.debug("PATCH /api/v1/users/{}", id);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "User updated", userManagementService.updateUserById(id, authentication, request)));
    }

    @Operation(summary = "Deactivate user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User deactivated"),
        @ApiResponse(responseCode = "400", description = "User is PENDING"),
        @ApiResponse(responseCode = "403", description = "Self-deactivation or cross-state attempt"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "409", description = "User is the last active admin in their role")
    })
    @PutMapping("/{id}/deactivate")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<Void>> deactivate(
            @Parameter(description = "User ID") @PathVariable Long id,
            Authentication authentication) {
        log.info("PUT /api/v1/users/[id]/deactivate");
        log.debug("PUT /api/v1/users/{}/deactivate", id);
        userManagementService.deactivateUser(id, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "User deactivated successfully"));
    }

    @Operation(summary = "Activate user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User activated"),
        @ApiResponse(responseCode = "400", description = "User is PENDING"),
        @ApiResponse(responseCode = "401", description = "Unauthorized cross-state activation attempt"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PutMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<Void>> activate(
            @Parameter(description = "User ID") @PathVariable Long id,
            Authentication authentication) {
        log.info("PUT /api/v1/users/[id]/activate");
        log.debug("PUT /api/v1/users/{}/activate", id);
        userManagementService.activateUser(id, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "User activated successfully"));
    }

    @Operation(summary = "Resend invite", description = "Re-send the invitation email to a PENDING user")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Invitation resent"),
        @ApiResponse(responseCode = "400", description = "User has already activated their account"),
        @ApiResponse(responseCode = "403", description = "STATE_ADMIN attempted cross-state reinvite"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PostMapping("/{id}/reinvite")
    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<Void>> reinvite(
            @Parameter(description = "User ID") @PathVariable Long id,
            Authentication authentication) {
        log.info("POST /api/v1/users/[id]/reinvite");
        log.debug("POST /api/v1/users/{}/reinvite", id);
        userManagementService.reinviteUser(id, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Invitation resent successfully"));
    }
}
