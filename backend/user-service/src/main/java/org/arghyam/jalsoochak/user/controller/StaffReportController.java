package org.arghyam.jalsoochak.user.controller;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffReportRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.ReportResponseDTO;
import org.arghyam.jalsoochak.user.enums.ReportFormat;
import org.arghyam.jalsoochak.user.service.StaffReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenant/user")
@RequiredArgsConstructor
@Validated
public class StaffReportController {

    private final StaffReportService staffReportService;

    /**
     * Generate (or return cached) a staff export in the requested format.
     * Body filters mirror {@code GET /staff} so the file content matches
     * what STATE_ADMIN sees in the UI for the same filters.
     */
    @PostMapping("/staff/reports")
    @PreAuthorize("hasRole('STATE_ADMIN') and @userSecurity.canAccessTenant(#tenantCode, authentication)")
    public ResponseEntity<ApiResponseDTO<ReportResponseDTO>> generateStaffReport(
            @RequestParam @NotBlank String tenantCode,
            @RequestParam(defaultValue = "CSV") String format,
            @RequestBody(required = false) StaffReportRequestDTO request,
            Authentication authentication
    ) {
        ReportFormat fmt = ReportFormat.fromString(format);
        log.info("POST /api/v1/tenant/user/staff/reports tenantCode={} format={} caller={}",
                tenantCode, fmt.key(), authentication != null ? authentication.getName() : "anonymous");
        ReportResponseDTO response = staffReportService.generate(tenantCode, fmt, request, authentication);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Staff report ready", response));
    }
}
