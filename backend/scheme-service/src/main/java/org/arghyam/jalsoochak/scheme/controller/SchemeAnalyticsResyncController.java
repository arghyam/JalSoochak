package org.arghyam.jalsoochak.scheme.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.arghyam.jalsoochak.scheme.dto.SchemeAnalyticsResyncResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Re-sends a tenant's schemes to analytics, to repair its scheme rows there. The tenant comes from
 * the {@code X-Tenant-Code} header.
 */
@RestController
@RequestMapping("/api/v1/scheme")
@RequiredArgsConstructor
@Slf4j
public class SchemeAnalyticsResyncController {

    private final SchemeService schemeService;

    @PreAuthorize("hasAnyRole('STATE_ADMIN','SUPER_STATE_ADMIN')")
    @PostMapping("/schemes/analytics-resync")
    public ResponseEntity<SchemeAnalyticsResyncResponseDTO> resyncSchemesToAnalytics() {
        log.info("POST /api/schemes/analytics-resync called");
        return ResponseEntity.ok(schemeService.resyncSchemesToAnalytics());
    }
}
