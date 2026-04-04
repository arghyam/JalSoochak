package org.arghyam.jalsoochak.user.controller;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/schemes")
@RequiredArgsConstructor
public class SchemeInsightsController {

    private final PersonSchemeService personSchemeService;

    @GetMapping("/{schemeId}/details")
    public ResponseEntity<ApiResponseDTO<SchemeDetailsWithReportingDTO>> getSchemeDetails(
            @PathVariable long schemeId,
            @RequestParam String tenantCode
    ) {
        SchemeDetailsWithReportingDTO dto = personSchemeService.getSchemeDetails(tenantCode, schemeId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found");
        }
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Scheme details retrieved", dto));
    }

    @GetMapping("/{schemeId}/reading-submissions")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<SchemeReadingSubmissionDTO>>> listSchemeReadings(
            @PathVariable long schemeId,
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponseDTO<SchemeReadingSubmissionDTO> rows = personSchemeService.listSchemeReadings(
                tenantCode,
                schemeId,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Scheme readings retrieved", rows));
    }
}
