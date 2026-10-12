package org.arghyam.jalsoochak.scheme.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.arghyam.jalsoochak.scheme.dto.SchemeUploadResponseDTO;
import org.arghyam.jalsoochak.scheme.dto.ReportLinkResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk CSV upload of schemes and their mappings, and the matching report downloads.
 */
@RestController
@RequestMapping("/api/v1/scheme")
@RequiredArgsConstructor
@Slf4j
public class SchemeBulkTransferController {

    private final SchemeService schemeService;

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
