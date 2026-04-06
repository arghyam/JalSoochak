package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationPaginatedResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.entity.Anomaly;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private static final int DEFAULT_ESCALATIONS_PAGE_NUMBER = 1;
    private static final int DEFAULT_ESCALATIONS_LIMIT = 10;

    private final FactSchemePerformanceRepository schemePerformanceRepository;
    private final SchemeRegularityService schemeRegularityService;
    private final EscalationQueryService escalationQueryService;
    private final AnomalyQueryService anomalyQueryService;

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
            summary = "Get paginated escalations list with filters",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Escalations fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = EscalationPaginatedResponse.class)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = EscalationPaginatedResponse.class)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = EscalationPaginatedResponse.class)
                            )
                    )
            }
    )
    public ResponseEntity<EscalationPaginatedResponse> getEscalationsPaginated(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "user_id") Integer userId,
            @RequestParam(name = "page_number", required = false, defaultValue = "1") Integer pageNumber,
            @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit,
            @RequestParam(name = "escalation_type", required = false) String escalationType,
            @RequestParam(name = "scheme_id", required = false) Integer schemeId,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("page_number must be >= 1");
            }
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be >= 1");
            }

            PageRequest pageable = PageRequest.of(pageNumber - 1, limit, Sort.by("createdAt").descending());
            Page<FactEscalation> page = escalationQueryService.getEscalations(
                    tenantId,
                    userId,
                    escalationType,
                    schemeId,
                    startDate,
                    endDate,
                    pageable
            );

            return ResponseEntity.ok(EscalationPaginatedResponse.builder()
                    .success(true)
                    .page(pageNumber)
                    .limit(limit)
                    .totalCount(page.getTotalElements())
                    .escalations(page.getContent())
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(EscalationPaginatedResponse.builder()
                    .success(false)
                    .page(pageNumber)
                    .limit(limit)
                    .totalCount(0)
                    .escalations(List.of())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(EscalationPaginatedResponse.builder()
                    .success(false)
                    .page(pageNumber)
                    .limit(limit)
                    .totalCount(0)
                    .escalations(List.of())
                    .build());
        }
    }

    @GetMapping("/anomalies")
    @Operation(summary = "Get anomalies for schemes mapped to a user (via dim_user_scheme_mapping_table)")
    public ResponseEntity<ApiResponse<List<Anomaly>>> getAnomalies(
            @RequestParam(name = "user_id") Integer mappedUserId,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "anomaly_type", required = false) String anomalyType
    ) {
        try {
            List<Anomaly> data = anomalyQueryService.getAnomaliesForUserSchemes(
                    mappedUserId,
                    startDate,
                    endDate,
                    anomalyType
            );

            return ResponseEntity.ok(ApiResponse.<List<Anomaly>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<Anomaly>>builder()
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
