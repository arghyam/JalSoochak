package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Scheme Reporting", description = "Scheme dashboards, region reports (CSV/JSON), escalations, and scheme performance queries")
public class AnalyticsSchemeReportingController {

    private static final String CSV_OUTPUT_FORMAT = "csv";

    private final FactEscalationRepository escalationRepository;
    private final FactSchemePerformanceRepository schemePerformanceRepository;
    private final SchemeRegularityService schemeRegularityService;

    @GetMapping("/schemes/status-count")
    @Operation(
            summary = "Get active and inactive scheme count for an LGD or department area",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Scheme status count fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEMES_STATUS_COUNT_SUCCESS)
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
    public ResponseEntity<ApiResponse<Map<String, Integer>>> getSchemeStatusCount(
            @RequestParam(name = "lgd_id", required = false) Integer lgdId,
            @RequestParam(name = "department_id", required = false) Integer departmentId) {
        try {
            if (lgdId != null && departmentId != null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id, not both");
            }
            if (lgdId == null && departmentId == null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id");
            }

            Map<String, Integer> data = (lgdId != null)
                    ? schemeRegularityService.getSchemeStatusCountByLgd(lgdId)
                    : schemeRegularityService.getSchemeStatusCountByDepartment(departmentId);

            return ResponseEntity.ok(ApiResponse.<Map<String, Integer>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<Map<String, Integer>>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<Map<String, Integer>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/schemes/dashboard")
    @Operation(
            summary = "Get active/inactive scheme count and top-N schemes by reporting rate for a parent LGD or parent department",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Schemes dashboard fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEMES_DASHBOARD_SUCCESS)
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
    public ResponseEntity<ApiResponse<SchemeStatusAndTopReportingResponse>> getSchemeStatusAndTopReportingRate(
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @RequestParam(name = "scheme_count", required = false, defaultValue = "10") Integer schemeCount) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id");
            }

            SchemeStatusAndTopReportingResponse data = (parentLgdId != null)
                    ? schemeRegularityService.getSchemeStatusAndTopReportingByLgd(parentLgdId, startDate, endDate, schemeCount)
                    : schemeRegularityService.getSchemeStatusAndTopReportingByDepartment(parentDepartmentId, startDate, endDate, schemeCount);

            return ResponseEntity.ok(ApiResponse.<SchemeStatusAndTopReportingResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<SchemeStatusAndTopReportingResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<SchemeStatusAndTopReportingResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/schemes/region-report")
    @Operation(
            summary = "Get all schemes with status, average regularity, submission rate and submission days for a parent LGD or parent department",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Region report fetched successfully (JSON) or CSV attachment when output_format=csv",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEMES_REGION_REPORT_JSON_SUCCESS)
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
    public ResponseEntity<?> getSchemeRegionReport(
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @RequestParam(name = "page_number", required = false) Integer pageNumber,
            @RequestParam(name = "count", required = false) Integer count,
            @Parameter(
                    description = "Output format for response",
                    required = false,
                    schema = @Schema(type = "string", allowableValues = {"json", "csv"}, defaultValue = "json", example = "csv"))
            @RequestParam(name = "output_format", required = false, defaultValue = "json") String outputFormat) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id");
            }

            SchemeRegularityListResponse reportResponse = (parentLgdId != null)
                    ? schemeRegularityService.getSchemeRegionReportByLgd(parentLgdId, startDate, endDate, pageNumber, count)
                    : schemeRegularityService.getSchemeRegionReportByDepartment(parentDepartmentId, startDate, endDate, pageNumber, count);

            if (!CSV_OUTPUT_FORMAT.equalsIgnoreCase(Objects.toString(outputFormat, ""))) {
                return ResponseEntity.ok(ApiResponse.<SchemeRegularityListResponse>builder()
                        .success(true)
                        .data(reportResponse)
                        .build());
            }

            String csvContent = AnalyticsControllerHelper.buildSchemeRegionReportCsv(reportResponse);
            String filename = AnalyticsControllerHelper.buildSchemeRegionReportFilename(reportResponse, startDate, endDate);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(csvContent);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/escalations")
    @Operation(
            summary = "Query escalation data by tenant or scheme",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Escalations fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.ESCALATIONS_SUCCESS)
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
    public ResponseEntity<ApiResponse<List<FactEscalation>>> getEscalations(
            @RequestParam(required = false) Integer tenantId,
            @RequestParam(required = false) Integer schemeId,
            @RequestParam(required = false) Integer resolutionStatus) {
        try {
            List<FactEscalation> data;
            if (tenantId != null && resolutionStatus != null) {
                data = escalationRepository.findByTenantIdAndResolutionStatus(tenantId, resolutionStatus);
            } else if (schemeId != null) {
                data = escalationRepository.findBySchemeId(schemeId);
            } else if (tenantId != null) {
                data = escalationRepository.findByTenantId(tenantId);
            } else {
                data = escalationRepository.findAll();
            }

            return ResponseEntity.ok(ApiResponse.<List<FactEscalation>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<FactEscalation>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/scheme-performance")
    @Operation(
            summary = "Query scheme performance data",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Scheme performance fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEME_PERFORMANCE_SUCCESS)
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
    public ResponseEntity<ApiResponse<List<FactSchemePerformance>>> getSchemePerformance(
            @RequestParam(required = false) Integer tenantId,
            @RequestParam(required = false) Integer schemeId) {
        try {
            List<FactSchemePerformance> data;
            if (schemeId != null) {
                data = schemePerformanceRepository.findBySchemeId(schemeId);
            } else if (tenantId != null) {
                data = schemePerformanceRepository.findByTenantId(tenantId);
            } else {
                data = schemePerformanceRepository.findAll();
            }

            return ResponseEntity.ok(ApiResponse.<List<FactSchemePerformance>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<FactSchemePerformance>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}

