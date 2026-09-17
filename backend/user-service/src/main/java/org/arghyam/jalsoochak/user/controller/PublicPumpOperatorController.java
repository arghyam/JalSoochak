package org.arghyam.jalsoochak.user.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard;
import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard.CallerScope;
import org.arghyam.jalsoochak.user.dto.common.ApiResponseDTO;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.PersonSchemeDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorDetailsWithComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingDetailDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceDTO;
import org.arghyam.jalsoochak.user.dto.response.PumpOperatorReadingComplianceRowDTO;
import org.arghyam.jalsoochak.user.dto.response.SchemeReadingComplianceRowDTO;
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
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Pump operator and scheme reads for two audiences.
 *
 * <p><b>Three routes are public</b> — {@code by-uuid/{uuid}}, {@code by-scheme} and
 * {@code by-scheme/reading-compliance} — because the anonymous village dashboard calls them. They
 * are bounded by the mandatory scheme filter, the page limits below and
 * {@code PublicApiEnumerationGuardFilter}, and they deliberately do not consult
 * {@link PumpOperatorAccessGuard}: an anonymous caller has no scope to check.
 *
 * <p><b>Every other route needs a token and is scoped per caller.</b> Each handler resolves a
 * {@link CallerScope} from the token and checks every object id against it; see
 * {@link PumpOperatorAccessGuard} for the rules and for why out-of-scope ids return 404 rather
 * than 403. On these routes {@code tenantCode} is not authoritative: for a caller with a tenant
 * claim it must match the claim, and only global admins use it to choose a tenant.
 */
@RestController
@RequestMapping("/api/v1/pumpoperator")
@RequiredArgsConstructor
@Validated
public class PublicPumpOperatorController {

    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Deepest page an anonymous caller may request. With a mandatory scheme filter no legitimate
     * public view comes anywhere near this; it exists so that "walk every page" is not expressible
     * even if a future endpoint loosens its filter.
     */
    private static final int MAX_PUBLIC_PAGE = 50;

    /**
     * Most schemes one {@code by-scheme} call may span. The village dashboard requests one scheme
     * per call, so this only bounds a caller trying to batch the whole tenant into one request.
     */
    private static final int MAX_SCHEME_IDS_PER_REQUEST = 25;

    private final PublicPumpOperatorService publicPumpOperatorService;
    private final PersonSchemeService personSchemeService;
    private final PumpOperatorAccessGuard accessGuard;

    @GetMapping("/pump-operators/{pumpOperatorId}")
    public ResponseEntity<ApiResponseDTO<PumpOperatorDetailsDTO>> getPumpOperatorDetails(
            @PathVariable long pumpOperatorId,
            @RequestParam(required = false) Long schemeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String tenantCode,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePumpOperatorAccess(scope, pumpOperatorId);
        if (schemeId != null) {
            accessGuard.requireSchemeAccess(scope, schemeId);
        }

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        PumpOperatorDetailsDTO dto = publicPumpOperatorService.getPumpOperatorDetails(
                scope.tenantCode(),
                pumpOperatorId,
                schemeId,
                startDate,
                endDate
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operator retrieved", dto));
    }

    /**
     * UUID-keyed operator detail — the route the anonymous village dashboard uses.
     *
     * <p>The sequential {@code /pump-operators/{pumpOperatorId}} route above is authenticated, so a
     * caller with no token cannot walk operator ids 1..N. {@code uuid} is {@code gen_random_uuid()}
     * (v4, random), so it is not guessable from a neighbouring record.
     */
    @GetMapping("/pump-operators/by-uuid/{uuid}")
    public ResponseEntity<ApiResponseDTO<PumpOperatorDetailsDTO>> getPumpOperatorDetailsByUuid(
            @PathVariable String uuid,
            @RequestParam(required = false) Long schemeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam String tenantCode
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        PumpOperatorDetailsDTO dto = publicPumpOperatorService.getPumpOperatorDetailsByUuid(
                tenantCode,
                uuid,
                schemeId,
                startDate,
                endDate
        );
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operator retrieved", dto));
    }

    @GetMapping("/pump-operators/{pumpOperatorId}/reading-compliance")
    public ResponseEntity<ApiResponseDTO<PumpOperatorReadingComplianceDTO>> getReadingCompliance(
            @PathVariable long pumpOperatorId,
            @RequestParam(required = false) String tenantCode,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePumpOperatorAccess(scope, pumpOperatorId);

        PumpOperatorReadingComplianceDTO dto = publicPumpOperatorService.getReadingCompliance(scope.tenantCode(), pumpOperatorId);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Reading compliance retrieved", dto));
    }

    @GetMapping("/pump-operators/{pumpOperatorId}/details-with-compliance")
    public ResponseEntity<ApiResponseDTO<PumpOperatorDetailsWithComplianceDTO>> getPumpOperatorDetailsWithCompliance(
            @PathVariable long pumpOperatorId,
            @RequestParam(required = false) String tenantCode,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePumpOperatorAccess(scope, pumpOperatorId);

        PumpOperatorDetailsWithComplianceDTO dto = publicPumpOperatorService.getPumpOperatorDetailsWithCompliance(scope.tenantCode(), pumpOperatorId);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Pump operator retrieved", dto));
    }

    /**
     * Every operator in the tenant, unscoped — tenant admins only. An officer's equivalent view is
     * {@code /person/{personId}/pump-operators}, which is limited to their own assignments.
     */
    @GetMapping("/pump-operators/reading-compliance")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PumpOperatorReadingComplianceRowDTO>>> listReadingCompliance(
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requireTenantWideAccess(scope);

        PageResponseDTO<PumpOperatorReadingComplianceRowDTO> rows = publicPumpOperatorService.listReadingCompliance(scope.tenantCode(), page, size);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Reading compliance retrieved", rows));
    }

    @GetMapping("/pump-operators/by-scheme/reading-compliance")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<SchemeReadingComplianceRowDTO>>> listSchemeReadingCompliance(
            @RequestParam String tenantCode,
            @RequestParam long schemeId,
            @RequestParam(required = false) Long pumpOperatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PUBLIC_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }
        PageResponseDTO<SchemeReadingComplianceRowDTO> rows =
                publicPumpOperatorService.listSchemeReadingCompliance(
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

        // A scheme filter is mandatory. Without one this endpoint took the unpaginated branch in
        // PublicPumpOperatorRepository#listPumpOperatorsByScheme and returned every operator in the
        // tenant — name, email and phone number — to an anonymous caller in a single response. The
        // MAX_PAGE_SIZE ceiling did not apply, because that branch only runs when the caller omits
        // page and size. schemeName alone is not a substitute: a one-character term matches most of
        // the table.
        if (effectiveSchemeIds == null || effectiveSchemeIds.isEmpty()) {
            throw new IllegalArgumentException("schemeId or schemeIds is required");
        }
        if (effectiveSchemeIds.size() > MAX_SCHEME_IDS_PER_REQUEST) {
            throw new IllegalArgumentException(
                    "at most " + MAX_SCHEME_IDS_PER_REQUEST + " schemeIds may be requested at once");
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
            // The same ceiling the @Max-annotated endpoints on this controller enforce. It is spelled
            // out here rather than annotated because this endpoint only paginates when asked to.
            if (effectiveSize < 1 || effectiveSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
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
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "readingAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String schemeName,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePumpOperatorAccess(scope, pumpOperatorId);

        PageResponseDTO<PumpOperatorReadingDetailDTO> rows = personSchemeService.listPumpOperatorReadings(
                scope.tenantCode(),
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
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String schemeName,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePersonAccess(scope, personId);

        long total = personSchemeService.countSchemesByPerson(scope.tenantCode(), personId, schemeName);
        return ResponseEntity.ok(ApiResponseDTO.of(200, "Schemes count retrieved", SchemeCountDTO.builder()
                .schemeCount(total)
                .build()));
    }

    @GetMapping("/person/{personId}/schemes")
    public ResponseEntity<ApiResponseDTO<PageResponseDTO<PersonSchemeDetailsDTO>>> listSchemesByPerson(
            @PathVariable long personId,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "schemeName") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(required = false) String schemeName,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePersonAccess(scope, personId);

        PageResponseDTO<PersonSchemeDetailsDTO> rows = personSchemeService.listSchemesByPerson(
                scope.tenantCode(),
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
            @RequestParam(required = false) String tenantCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer durationDays,
            @RequestParam(required = false) Integer duration,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication
    ) {
        CallerScope scope = accessGuard.resolve(authentication, tenantCode);
        accessGuard.requirePersonAccess(scope, personId);

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("start_date must be on or before end_date");
        }
        Integer effectiveDurationDays = duration != null ? duration : durationDays;
        PageResponseDTO<PumpOperatorSummaryWithMetricsDTO> rows = personSchemeService.listPumpOperatorsByPerson(
                scope.tenantCode(),
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
