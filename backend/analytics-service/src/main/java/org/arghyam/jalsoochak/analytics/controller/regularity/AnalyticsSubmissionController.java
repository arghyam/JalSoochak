package org.arghyam.jalsoochak.analytics.controller.regularity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.enums.RegularityScope;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Reading submission rates, raw meter readings, submission status and non-submission reasons.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Reading Submission", description = "Reading submission rates, meter readings, submission status, and non-submission reason distributions")
@Slf4j
public class AnalyticsSubmissionController {

    private final SchemeRegularityService schemeRegularityService;
    private final FactMeterReadingRepository meterReadingRepository;
    private final DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;

    @GetMapping("/reading-submission-rate")
    @Operation(
            summary = "Get reading submission rate for current area or immediate children (scope=current|child) within a date range",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Reading submission rate fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.READING_SUBMISSION_RATE_SUCCESS)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE)
                            )
                    )
            }
    )
    public ResponseEntity<ApiResponse<ReadingSubmissionRateResponse>> getReadingSubmissionRateByLgd(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @Parameter(description = "Response scope", required = false, schema = @Schema(type = "string", allowableValues = {
                    "current",
                    "child" }, defaultValue = "current")) @RequestParam(name = "scope", defaultValue = "current") String scope,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            RegularityScope regularityScope = RegularityScope.fromValue(scope);

            ReadingSubmissionRateResponse data;
            if (regularityScope == RegularityScope.CHILD) {
                if (parentDepartmentId != null) {
                    data = schemeRegularityService.getReadingSubmissionRateByDepartmentForChildRegions(
                            tenantId, parentDepartmentId, startDate, endDate);
                } else {
                    data = schemeRegularityService.getReadingSubmissionRateByLgdForChildRegions(tenantId, parentLgdId, startDate,
                            endDate);
                }
            } else if (parentDepartmentId != null) {
                data = schemeRegularityService.getReadingSubmissionRateByDepartment(tenantId, parentDepartmentId, startDate,
                        endDate);
            } else {
                data = schemeRegularityService.getReadingSubmissionRateByLgd(tenantId, parentLgdId, startDate, endDate);
            }

            return ResponseEntity.ok(ApiResponse.<ReadingSubmissionRateResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<ReadingSubmissionRateResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed /reading-submission-rate (tenantId={}, parentLgdId={}, parentDepartmentId={}, scope={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, scope, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<ReadingSubmissionRateResponse>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }

    @GetMapping("/meter-readings")
    @Operation(
            summary = "Query meter readings by tenant + scheme and optional date range",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Meter readings fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.METER_READINGS_SUCCESS)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE)
                            )
                    )
            }
    )
    public ResponseEntity<ApiResponse<List<FactMeterReading>>> getMeterReadings(
            @RequestParam(name = "tenant_id", required = true) Integer tenantId,
            @RequestParam(name = "scheme_id", required = true) Integer schemeId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            if (startDate == null && endDate == null) {
                // Preserve existing (start_date > end_date) semantics, but anchor defaults to "yesterday" (IST)
                // so they complement the daily (midnight) warm-cache window.
                DefaultAnalyticsDateWindowProvider.DateWindow window =
                        defaultAnalyticsDateWindowProvider.defaultWindow();
                startDate = window.endDate();
                endDate = window.startDate();
            } else if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("Provide both start_date and end_date together");
            }
            if (!startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("start_date must be after end_date");
            }

            List<FactMeterReading> data = meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(
                    tenantId, schemeId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<List<FactMeterReading>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<List<FactMeterReading>>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed GET /meter-readings (tenantId={}, schemeId={}, startDate={}, endDate={})",
                    tenantId, schemeId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<FactMeterReading>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/non-submission-reasons")
    @Operation(
            summary = "Get non submission reason wise scheme count for an LGD or department area",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Non-submission reasons fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.NON_SUBMISSION_REASONS_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<NonSubmissionReasonSchemeCountResponse>> getNonSubmissionReasonWiseSchemeCount(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id");
            }

            NonSubmissionReasonSchemeCountResponse data = (parentLgdId != null)
                    ? schemeRegularityService.getNonSubmissionReasonSchemeCountByLgd(tenantId, parentLgdId, startDate, endDate)
                    : schemeRegularityService.getNonSubmissionReasonSchemeCountByDepartment(tenantId, parentDepartmentId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<NonSubmissionReasonSchemeCountResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<NonSubmissionReasonSchemeCountResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed /non-submission-reasons (tenantId={}, parentLgdId={}, parentDepartmentId={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NonSubmissionReasonSchemeCountResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/non-submission-reasons/user")
    @Operation(
            summary = "Get non submission reason wise scheme count for a user",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Non-submission reasons (user) fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.NON_SUBMISSION_REASONS_USER_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    @PreAuthorize("hasAnyAuthority('USER_TYPE_SECTION_OFFICER', 'USER_TYPE_SUB_DIVISIONAL_OFFICER')")
    public ResponseEntity<ApiResponse<UserNonSubmissionReasonSchemeCountResponse>> getNonSubmissionReasonWiseSchemeCountByUser(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
        UserNonSubmissionReasonSchemeCountResponse data = userRef.userId() != null
                ? schemeRegularityService.getNonSubmissionReasonSchemeCountByUser(userRef.tenantId(), userRef.userId(), startDate, endDate)
                : schemeRegularityService.getNonSubmissionReasonSchemeCountByUserUuid(userRef.tenantId(), userRef.userUuid(), startDate, endDate);
        return ResponseEntity.ok(ApiResponse.<UserNonSubmissionReasonSchemeCountResponse>builder()
                .success(true)
                .data(data)
                .build());
    }

    @GetMapping("/submission-status/user")
    @Operation(
            summary = "Get submission status counts for a user",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Submission status (user) fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SUBMISSION_STATUS_USER_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request"
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    @PreAuthorize("hasAnyAuthority('USER_TYPE_SECTION_OFFICER', 'USER_TYPE_SUB_DIVISIONAL_OFFICER')")
    public ResponseEntity<ApiResponse<UserSubmissionStatusResponse>> getSubmissionStatusByUser(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
        UserSubmissionStatusResponse data = userRef.userId() != null
                ? schemeRegularityService.getSubmissionStatusByUser(userRef.tenantId(), userRef.userId(), startDate, endDate)
                : schemeRegularityService.getSubmissionStatusByUserUuid(userRef.tenantId(), userRef.userUuid(), startDate, endDate);
        return ResponseEntity.ok(ApiResponse.<UserSubmissionStatusResponse>builder()
                .success(true)
                .data(data)
                .build());
    }

    @GetMapping("/submission-status")
    @Operation(
            summary = "Get scheme count and compliant/anomalous submission counts for an LGD or department",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Submission status summary fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SUBMISSION_STATUS_SUMMARY_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<SubmissionStatusSummaryResponse>> getSubmissionStatusSummary(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "lgd_id", required = false) Integer lgdId,
            @RequestParam(name = "department_id", required = false) Integer departmentId) {
        try {
            if (lgdId != null && departmentId != null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id, not both");
            }
            if (lgdId == null && departmentId == null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id");
            }

            SubmissionStatusSummaryResponse data = (lgdId != null)
                    ? schemeRegularityService.getSubmissionStatusSummaryByLgd(tenantId, lgdId, startDate, endDate)
                    : schemeRegularityService.getSubmissionStatusSummaryByDepartment(tenantId, departmentId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<SubmissionStatusSummaryResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<SubmissionStatusSummaryResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed /submission-status (tenantId={}, lgdId={}, departmentId={}, startDate={}, endDate={})",
                    tenantId, lgdId, departmentId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<SubmissionStatusSummaryResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}
