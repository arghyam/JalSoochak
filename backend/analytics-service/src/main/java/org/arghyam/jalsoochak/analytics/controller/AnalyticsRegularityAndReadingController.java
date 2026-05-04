package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.RegularityScope;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Regularity & Reading Submission", description = "Scheme regularity and reading submission rate metrics")
public class AnalyticsRegularityAndReadingController {

    private final SchemeRegularityService schemeRegularityService;

    @GetMapping("/scheme-regularity/average")
    @Operation(
            summary = "Get average scheme regularity for current area or immediate children (scope=current|child) within a date range",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Average scheme regularity fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEME_REGULARITY_AVERAGE_SUCCESS)
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
    public ResponseEntity<ApiResponse<AverageSchemeRegularityResponse>> getAverageSchemeRegularity(
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

            AverageSchemeRegularityResponse data;
            if (regularityScope == RegularityScope.CHILD) {
                if (parentDepartmentId != null) {
                    data = schemeRegularityService.getAverageSchemeRegularityByDepartmentForChildRegions(
                            tenantId, parentDepartmentId, startDate, endDate);
                } else {
                    data = schemeRegularityService.getAverageSchemeRegularityForChildRegions(tenantId, parentLgdId, startDate,
                            endDate);
                }
            } else if (parentDepartmentId != null) {
                data = schemeRegularityService.getAverageSchemeRegularityByDepartment(tenantId, parentDepartmentId, startDate,
                        endDate);
            } else {
                data = schemeRegularityService.getAverageSchemeRegularity(tenantId, parentLgdId, startDate, endDate);
            }

            return ResponseEntity.ok(ApiResponse.<AverageSchemeRegularityResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<AverageSchemeRegularityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<AverageSchemeRegularityResponse>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }

    @GetMapping("/scheme-regularity/periodic")
    @Operation(
            summary = "Get periodic average scheme regularity for an LGD ID or department",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Periodic scheme regularity fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEME_REGULARITY_PERIODIC_SUCCESS)
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
    public ResponseEntity<ApiResponse<PeriodicSchemeRegularityResponse>> getPeriodicSchemeRegularity(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(
                    description = """
                            Time aggregation scale.
                            - day: per-day buckets
                            - week: rolling 7-day buckets anchored to start_date (not ISO-week aligned)
                            - month/quarter/year: calendar-aligned buckets (month=Jan/Feb..., quarter=Jan-Mar/Apr-Jun..., year=Jan 1-Dec 31)
                            """,
                    required = true,
                    schema = @Schema(type = "string", allowableValues = {"day", "week", "month", "quarter", "year"}))
            @RequestParam(name = "scale") String scale,
            @RequestParam(name = "lgd_id", required = false) Integer lgdId,
            @RequestParam(name = "department_id", required = false) Integer departmentId) {
        try {
            if (lgdId != null && departmentId != null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id, not both");
            }
            if (lgdId == null && departmentId == null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id");
            }
            PeriodScale periodScale = PeriodScale.fromValue(scale);

            PeriodicSchemeRegularityResponse data = (lgdId != null)
                    ? schemeRegularityService.getPeriodicSchemeRegularityByLgdId(tenantId, lgdId, startDate, endDate, periodScale)
                    : schemeRegularityService.getPeriodicSchemeRegularityByDepartment(tenantId, departmentId, startDate, endDate,
                            periodScale);

            return ResponseEntity.ok(ApiResponse.<PeriodicSchemeRegularityResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<PeriodicSchemeRegularityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<PeriodicSchemeRegularityResponse>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }

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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<ReadingSubmissionRateResponse>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }
}
