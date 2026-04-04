package org.arghyam.jalsoochak.user.controller;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorSchemeComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemePumpOperatorsDTO;
import org.arghyam.jalsoochak.user.service.PublicPumpOperatorService;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam String tenantCode
    ) {
        PumpOperatorDetailsDTO dto = publicPumpOperatorService.getPumpOperatorDetails(tenantCode, pumpOperatorId);
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageResponseDTO<PumpOperatorSchemeComplianceRowDTO> rows =
                publicPumpOperatorService.listPumpOperatorsBySchemeWithCompliance(tenantCode, schemeId, page, size);
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
}
