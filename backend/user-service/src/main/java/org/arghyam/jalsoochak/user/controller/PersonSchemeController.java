package org.arghyam.jalsoochak.user.controller;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeCountDTO;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/person")
@RequiredArgsConstructor
public class PersonSchemeController {

    private final PersonSchemeService personSchemeService;

    @GetMapping("/{personId}/schemes/count")
    public ResponseEntity<ApiResponseDTO<SchemeCountDTO>> countSchemesByPerson(
            @PathVariable long personId,
            @RequestParam String tenantCode,
            @RequestParam(required = false) String schemeName
    ) {
        long total = personSchemeService.countSchemesByPerson(tenantCode, personId, schemeName);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Schemes count retrieved", SchemeCountDTO.builder()
                .schemeCount(total)
                .build()));
    }

    @GetMapping("/{personId}/schemes")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PersonSchemeDetailsDTO>>> listSchemesByPerson(
            @PathVariable long personId,
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "schemeName") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(required = false) String schemeName
    ) {
        PageResponseDTO<PersonSchemeDetailsDTO> rows = personSchemeService.listSchemesByPerson(
                tenantCode,
                personId,
                schemeName,
                sortBy,
                sortDir,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Schemes retrieved", rows));
    }

    @GetMapping("/{personId}/pump-operators")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PumpOperatorSummaryWithMetricsDTO>>> listPumpOperatorsByPerson(
            @PathVariable long personId,
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer durationDays
    ) {
        PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> rows = personSchemeService.listPumpOperatorsByPerson(
                tenantCode,
                personId,
                name,
                status,
                durationDays,
                sortBy,
                sortDir,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operators retrieved", rows));
    }
}
