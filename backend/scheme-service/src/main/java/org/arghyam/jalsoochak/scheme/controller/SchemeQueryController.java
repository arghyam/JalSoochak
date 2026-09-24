package org.arghyam.jalsoochak.scheme.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.arghyam.jalsoochak.scheme.config.RequiresTenantAccess;
import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusBreakdownDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeYesterdayFinalReadingDTO;
import org.arghyam.jalsoochak.scheme.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped reads of schemes, their village mappings and their status counts.
 */
@RestController
@RequestMapping("/api/v1/scheme")
@RequiredArgsConstructor
@Slf4j
public class SchemeQueryController {

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
            @RequestParam(required = false) List<String> workStatus,
            @RequestParam(required = false, name = "workstatus") List<String> workstatus,
            @RequestParam(required = false) List<String> operatingStatus,
            @RequestParam(required = false, name = "operatingstatus") List<String> operatingstatus
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
                firstNonEmpty(workStatus, workstatus),
                firstNonEmpty(operatingStatus, operatingstatus)
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

    private static List<String> firstNonEmpty(List<String> a, List<String> b) {
        if (a != null && !a.isEmpty()) {
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
            @RequestParam(required = false) List<String> workStatus,
            @RequestParam(required = false) List<String> operatingStatus,
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
                villageLgdCode,
                subDivisionName
        ));
    }

    @RequiresTenantAccess
    @GetMapping("/schemes/counts/by-status")
    public ResponseEntity<SchemeStatusBreakdownDTO> getSchemeStatusCounts(
            @RequestParam String tenantCode
    ) {
        log.info("GET /api/schemes/counts/by-status called");
        return ResponseEntity.ok(schemeService.getSchemeStatusCounts(tenantCode));
    }
}
