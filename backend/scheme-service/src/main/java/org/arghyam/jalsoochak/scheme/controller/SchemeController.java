package org.arghyam.jalsoochak.scheme.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.scheme.dto.SchemeCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.ReportLinkResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeYesterdayFinalReadingDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/scheme")
@RequiredArgsConstructor
@Slf4j
public class SchemeController {

    private final SchemeService schemeService;

    @RequiresTenantAccess
    @GetMapping("/schemes")
    public ResponseEntity<PageResponseDTO<SchemeDTO>> listSchemes(
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String stateSchemeId,
            @RequestParam(required = false) String schemeName,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String workStatus,
            @RequestParam(required = false, name = "workstatus") String workstatus,
            @RequestParam(required = false) String operatingStatus,
            @RequestParam(required = false, name = "operatingstatus") String operatingstatus,
            @RequestParam(required = false) String status
    ) {
        log.info("GET /api/schemes called");
        return ResponseEntity.ok(schemeService.listSchemes(
                tenantCode,
                page,
                limit,
                sortBy,
                sortDir,
                stateSchemeId,
                schemeName,
                name,
                firstNonBlank(workStatus, workstatus),
                firstNonBlank(operatingStatus, operatingstatus),
                status
        ));
    }

    @RequiresTenantAccess
    @GetMapping("/schemes/yesterday-final-readings")
    public ResponseEntity<PageResponseDTO<SchemeYesterdayFinalReadingDTO>> listSchemesWithYesterdayFinalReading(
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String schemeName
    ) {
        log.info("GET /api/v1/scheme/schemes/yesterday-final-readings called");
        return ResponseEntity.ok(schemeService.listSchemesWithYesterdayFinalReading(tenantCode, page, limit, schemeName));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    @RequiresTenantAccess
    @GetMapping("/schemes/mappings")
    public ResponseEntity<PageResponseDTO<SchemeMappingDTO>> listSchemeMappings(
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String schemeName,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String workStatus,
            @RequestParam(required = false) String operatingStatus,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String villageLgdCode,
            @RequestParam(required = false) String subDivisionName
    ) {
        log.info("GET /api/schemes/mappings called");
        return ResponseEntity.ok(schemeService.listSchemeMappings(
                tenantCode,
                page,
                limit,
                sortBy,
                sortDir,
                firstNonBlank(schemeName, name),
                workStatus,
                operatingStatus,
                status,
                villageLgdCode,
                subDivisionName
        ));
    }

    @RequiresTenantAccess
    @GetMapping("/schemes/counts")
    public ResponseEntity<SchemeCountsDTO> getSchemeCounts(
            @RequestParam String tenantCode
    ) {
        log.info("GET /api/schemes/counts called");
        return ResponseEntity.ok(schemeService.getSchemeCounts(tenantCode));
    }

    @RequiresTenantAccess
    @GetMapping("/schemes/counts/by-status")
    public ResponseEntity<SchemeStatusCountsDTO> getSchemeStatusCounts(
            @RequestParam String tenantCode
    ) {
        log.info("GET /api/schemes/counts/by-status called");
        return ResponseEntity.ok(schemeService.getSchemeStatusCounts(tenantCode));
    }

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

    @PreAuthorize("hasAnyRole('STATE_ADMIN','SUPER_STATE_ADMIN')")
    @PostMapping(value = "/schemes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SchemeUploadResponseDTO> uploadSchemes(
            @RequestParam("file") MultipartFile file
    ) {
        log.info("POST /api/schemes/upload called with file: {}", file.getOriginalFilename());
        return ResponseEntity.ok(schemeService.uploadSchemes(file));
    }

    @PreAuthorize("hasAnyRole('STATE_ADMIN','SUPER_STATE_ADMIN')")
    @PostMapping(value = "/schemes/mappings/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SchemeUploadResponseDTO> uploadSchemeMappings(
            @RequestParam("file") MultipartFile file
    ) {
        log.info("POST /api/schemes/mappings/upload called with file: {}", file.getOriginalFilename());
        return ResponseEntity.ok(schemeService.uploadSchemeMappings(file));
    }

    @PreAuthorize("hasAnyRole('STATE_ADMIN','SUPER_STATE_ADMIN')")
    @GetMapping("/schemes/download")
    public ResponseEntity<ReportLinkResponseDTO> downloadSchemes() {
        log.info("GET /api/schemes/download called");
        return ResponseEntity.ok(schemeService.downloadSchemesReport());
    }

    @PreAuthorize("hasAnyRole('STATE_ADMIN','SUPER_STATE_ADMIN')")
    @GetMapping("/schemes/mappings/download")
    public ResponseEntity<ReportLinkResponseDTO> downloadSchemeMappings() {
        log.info("GET /api/schemes/mappings/download called");
        return ResponseEntity.ok(schemeService.downloadSchemeMappingsReport());
    }
}
