package org.arghyam.jalsoochak.user.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateStaffRoleRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.WelcomeMessageResponseDTO;
import org.arghyam.jalsoochak.user.service.TenantStaffService;
import org.arghyam.jalsoochak.user.service.WelcomeMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenant/user")
@RequiredArgsConstructor
@Validated
public class TenantStaffController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TenantStaffService tenantStaffService;
    private final WelcomeMessageService welcomeMessageService;

    @GetMapping("/staff")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<TenantStaffResponseDTO>>> listStaff(
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int limit,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) List<String> role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name
    ) {
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Staff retrieved",
                tenantStaffService.listStaff(tenantCode, page, limit, sortBy, sortDir, role, status, name)));
    }

    @PutMapping("/staff/{id}/role")
    @PreAuthorize("hasRole('STATE_ADMIN')")
    public ResponseEntity<ApiResponseDTO<TenantStaffResponseDTO>> updateStaffRole(
            @PathVariable @Positive Long id,
            @Valid @RequestBody UpdateStaffRoleRequestDTO request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Staff role updated",
                tenantStaffService.updateStaffRole(id, request, authentication)));
    }

    @GetMapping("/staff/counts/by-role")
    public ResponseEntity<ApiResponseDTO<List<RoleCountDTO>>> countStaffByRole(
            @RequestParam String tenantCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name
    ) {
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Staff counts retrieved",
                tenantStaffService.countStaffByRole(tenantCode, status, name)));
    }

    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    @PostMapping("/staff/{id}/deactivate")
    public ResponseEntity<ApiResponseDTO<Void>> deactivateStaff(
            @PathVariable @Positive Long id,
            @RequestParam @NotBlank String tenantCode,
            Authentication authentication) {
        log.info("POST /api/v1/tenant/user/staff/{}/deactivate tenantCode={} caller={}",
                id, tenantCode, authentication != null ? authentication.getName() : "anonymous");
        tenantStaffService.deactivateStaff(id, tenantCode, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Staff user deactivated successfully"));
    }

    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    @PostMapping("/staff/{id}/activate")
    public ResponseEntity<ApiResponseDTO<Void>> activateStaff(
            @PathVariable @Positive Long id,
            @RequestParam @NotBlank String tenantCode,
            Authentication authentication) {
        log.info("POST /api/v1/tenant/user/staff/{}/activate tenantCode={} caller={}",
                id, tenantCode, authentication != null ? authentication.getName() : "anonymous");
        tenantStaffService.activateStaff(id, tenantCode, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Staff user activated successfully"));
    }

    @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN')")
    @PostMapping("/welcome")
    public ResponseEntity<ApiResponseDTO<WelcomeMessageResponseDTO>> sendWelcomeMessages(
            @RequestParam String tenantCode,
            @Valid @RequestBody WelcomeMessageRequestDTO request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Welcome messages queued",
                welcomeMessageService.sendWelcomeMessages(tenantCode, request, authentication)));
    }
}
