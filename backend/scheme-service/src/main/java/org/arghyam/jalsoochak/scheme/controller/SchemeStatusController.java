package org.arghyam.jalsoochak.scheme.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads and updates one scheme's work and operating status.
 */
@RestController
@RequestMapping("/api/v1/scheme")
@RequiredArgsConstructor
@Slf4j
public class SchemeStatusController {

    private final SchemeService schemeService;

    @PreAuthorize("@schemeSecurity.canAccessTenantId(#tenantId, authentication)")
    @GetMapping("/schemes/{schemeId}/statuses")
    public ResponseEntity<SchemeStatusesResponseDTO> getSchemeStatuses(
            @PathVariable int schemeId,
            @RequestParam Integer tenantId
    ) {
        log.info("GET /api/schemes/{}/statuses called for tenantId {}", schemeId, tenantId);
        return ResponseEntity.ok(schemeService.getSchemeStatuses(tenantId, schemeId));
    }

    @PreAuthorize("hasRole('STATE_ADMIN') and @schemeSecurity.canAccessTenant(#tenantCode, authentication)")
    @PatchMapping("/schemes/{schemeId}/status")
    public ResponseEntity<Void> updateSchemeStatuses(
            @RequestParam String tenantCode,
            @PathVariable int schemeId,
            @RequestBody SchemeStatusUpdateRequestDTO request
    ) {
        log.info("PATCH /api/schemes/{}/status called", schemeId);
        schemeService.updateSchemeStatuses(tenantCode, schemeId, request);
        return ResponseEntity.noContent().build();
    }
}
