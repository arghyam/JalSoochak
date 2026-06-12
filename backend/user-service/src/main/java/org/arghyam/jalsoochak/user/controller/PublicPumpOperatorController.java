package org.arghyam.jalsoochak.user.controller;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSummaryWithMetricsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeCountDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.service.PublicPumpOperatorService;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/pumpoperator")
@RequiredArgsConstructor
public class PublicPumpOperatorController {

    private final PublicPumpOperatorService publicPumpOperatorService;
    private final PersonSchemeService personSchemeService;

    @GetMapping("/pump-operators/{pumpOperatorId}")
    public ResponseEntity<ApiResponseDTO<PumpOperatorDetailsDTO>> getPumpOperatorDetails(
            @PathVariable long pumpOperatorId,
            @RequestParam long schemeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam String tenantCode
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        PumpOperatorDetailsDTO dto = publicPumpOperatorService.getPumpOperatorDetails(
                tenantCode,
                pumpOperatorId,
                schemeId,
                startDate,
                endDate
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operator retrieved", dto));
    }

    @GetMapping("/pump-operators/{pumpOperatorId}/reading-compliance")
    public ResponseEntity<ApiResponseDTO<PumpOperatorReadingComplianceDTO>> getReadingCompliance(
            @PathVariable long pumpOperatorId,
            @RequestParam String tenantCode
    ) {
        PumpOperatorReadingComplianceDTO dto = publicPumpOperatorService.getReadingCompliance(tenantCode, pumpOperatorId);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Reading compliance retrieved", dto));
    }

    @GetMapping("/pump-operators/{pumpOperatorId}/details-with-compliance")
    public ResponseEntity<ApiResponseDTO<PumpOperatorDetailsWithComplianceDTO>> getPumpOperatorDetailsWithCompliance(
            @PathVariable long pumpOperatorId,
            @RequestParam String tenantCode
    ) {
        PumpOperatorDetailsWithComplianceDTO dto = publicPumpOperatorService.getPumpOperatorDetailsWithCompliance(tenantCode, pumpOperatorId);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operator retrieved", dto));
    }

    @GetMapping("/pump-operators/reading-compliance")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PumpOperatorReadingComplianceRowDTO>>> listReadingCompliance(
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponseDTO<PumpOperatorReadingComplianceRowDTO> rows = publicPumpOperatorService.listReadingCompliance(tenantCode, page, size);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Reading compliance retrieved", rows));
    }

    @GetMapping("/pump-operators/by-scheme/reading-compliance")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PumpOperatorSchemeComplianceRowDTO>>> listPumpOperatorsBySchemeWithCompliance(
            @RequestParam String tenantCode,
            @RequestParam long schemeId,
            @RequestParam long pumpOperatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> rows =
                publicPumpOperatorService.listPumpOperatorsBySchemeWithCompliance(
                        tenantCode,
                        schemeId,
                        pumpOperatorId,
                        startDate,
                        endDate,
                        page,
                        size
                );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operators retrieved", rows));
    }

    @GetMapping("/pump-operators/by-scheme")
    public ResponseEntity<ApiResponseDTO<List<SchemePumpOperatorsDTO>>> listPumpOperatorsByScheme(
            @RequestParam String tenantCode,
            @RequestParam(required = false) Long schemeId,
            @RequestParam(required = false) List<Long> schemeIds,
            @RequestParam(required = false) String schemeName,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        List<Long> effectiveSchemeIds = schemeIds;
        if (schemeId != null) {
            effectiveSchemeIds = List.of(schemeId);
        }

        // Backwards-compatible: only paginate when caller provides page and/or size.
        Integer effectivePage = null;
        Integer effectiveSize = null;
        if (page != null || size != null) {
            effectivePage = page == null ? 0 : page;
            effectiveSize = size == null ? 20 : size;
            if (effectivePage < 0) {
                throw new IllegalArgumentException("page must be >= 0");
            }
            if (effectiveSize < 1 || effectiveSize > 500) {
                throw new IllegalArgumentException("size must be between 1 and 500");
            }
        }

        List<SchemePumpOperatorsDTO> rows = publicPumpOperatorService.listPumpOperatorsByScheme(
                tenantCode,
                effectiveSchemeIds,
                schemeName,
                effectivePage,
                effectiveSize
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operators retrieved", rows));
    }

    @GetMapping("/pump-operators/{pumpOperatorId}/readings")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PumpOperatorReadingDetailDTO>>> listPumpOperatorReadings(
            @PathVariable long pumpOperatorId,
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "readingAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String schemeName
    ) {
        PageResponseDTO<PumpOperatorReadingDetailDTO> rows = personSchemeService.listPumpOperatorReadings(
                tenantCode,
                pumpOperatorId,
                schemeName,
                sortBy,
                sortDir,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operator readings retrieved", rows));
    }

    @GetMapping("/person/{personId}/schemes/count")
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

    @GetMapping("/person/{personId}/schemes")
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

    @GetMapping("/person/{personId}/pump-operators")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PumpOperatorSummaryWithMetricsDTO>>> listPumpOperatorsByPerson(
            @PathVariable long personId,
            @RequestParam String tenantCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer durationDays,
            @RequestParam(required = false) Integer duration,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("start_date must be on or before end_date");
        }
        Integer effectiveDurationDays = duration != null ? duration : durationDays;
        PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> rows = personSchemeService.listPumpOperatorsByPerson(
                tenantCode,
                personId,
                name,
                status,
                effectiveDurationDays,
                startDate,
                endDate,
                sortBy,
                sortDir,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operators retrieved", rows));
    }

    @GetMapping("/schemes/{schemeId}/details")
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

    @GetMapping("/schemes/{schemeId}/reading-submissions")
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
