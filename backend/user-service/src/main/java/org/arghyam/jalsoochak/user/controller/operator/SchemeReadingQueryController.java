package org.arghyam.jalsoochak.user.controller.operator;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard;
import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard.CallerScope;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeDetailsWithReportingDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingSubmissionDTO;
import org.arghyam.jalsoochak.user.service.PersonSchemeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A scheme's details and reading submissions.
 *
 * <p>Both routes need a token and are scoped per caller: each handler resolves a {@link CallerScope}
 * from the token and checks the scheme id against it. See {@link PumpOperatorAccessGuard} for the
 * rules and for why an out-of-scope id returns 404 rather than 403.
 */
@RestController
@RequestMapping("/api/v1/pumpoperator")
@RequiredArgsConstructor
@Validated
public class SchemeReadingQueryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PersonSchemeService personSchemeService;
    private final PumpOperatorAccessGuard accessGuard;

    @GetMapping("/schemes/{schemeId}/details")
    public ResponseEntity<ApiResponseDTO<SchemeDetailsWithReportingDTO>> getSchemeDetails(
            @PathVariable long schemeId,
            @RequestParam(required = false) String tenantCode,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requireSchemeAccess(scope, schemeId);

        SchemeDetailsWithReportingDTO dto = personSchemeService.getSchemeDetails(scope.tenantCode(), schemeId);
        if (dto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheme not found");
        }
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Scheme details retrieved", dto));
    }

    @GetMapping("/schemes/{schemeId}/reading-submissions")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<SchemeReadingSubmissionDTO>>> listSchemeReadings(
            @PathVariable long schemeId,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requireSchemeAccess(scope, schemeId);

        PageResponseDTO<SchemeReadingSubmissionDTO> rows = personSchemeService.listSchemeReadings(
                scope.tenantCode(),
                schemeId,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Scheme readings retrieved", rows));
    }
}
