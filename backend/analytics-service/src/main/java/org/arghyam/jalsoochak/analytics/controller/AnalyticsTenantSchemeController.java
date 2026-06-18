package org.arghyam.jalsoochak.analytics.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantBoundaryGeoJsonResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceScoreResponse;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics - Tenants & Schemes", description = "Tenant metadata, scheme dimensions, and raw meter reading queries")
@Slf4j
public class AnalyticsTenantSchemeController {

    private final DimTenantRepository dimTenantRepository;
    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final DimSchemeRepository dimSchemeRepository;
    private final FactMeterReadingRepository meterReadingRepository;
    private final TenantDetailsService tenantDetailsService;
    private final DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    public AnalyticsTenantSchemeController(
            DimTenantRepository dimTenantRepository,
            DimLgdLocationRepository dimLgdLocationRepository,
            DimSchemeRepository dimSchemeRepository,
            FactMeterReadingRepository meterReadingRepository,
            TenantDetailsService tenantDetailsService,
            DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider
    ) {
        this.dimTenantRepository = dimTenantRepository;
        this.dimLgdLocationRepository = dimLgdLocationRepository;
        this.dimSchemeRepository = dimSchemeRepository;
        this.meterReadingRepository = meterReadingRepository;
        this.tenantDetailsService = tenantDetailsService;
        this.defaultAnalyticsDateWindowProvider = defaultAnalyticsDateWindowProvider;
    }

    @GetMapping("/tenants")
    @Operation(
            summary = "List all tenants in the DW",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Tenants fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "success",
                                            value = SwaggerExamples.TENANTS_SUCCESS
                                    )
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(
                                            name = "failure",
                                            value = SwaggerExamples.GENERIC_FAILURE
                                    )
                            )
                    )
            }
    )
    public ResponseEntity<ApiResponse<List<DimTenant>>> getTenants() {
        try {
            return ResponseEntity.ok(ApiResponse.<List<DimTenant>>builder()
                    .success(true)
                    .data(dimTenantRepository.findByTenantIdGreaterThan(0))
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /tenants", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<DimTenant>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/tenant_data")
    @Operation(
            summary = "Get tenant data, filtered by parent_lgd_id or parent_department_id",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Tenant details fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.TENANT_DATA_SUCCESS)
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
    public ResponseEntity<ApiResponse<TenantDetailsResponse>> getTenantDetails(
            @RequestParam(name = "tenant_id", required = true) Integer tenantId,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            if (startDate == null && endDate == null) {
                DefaultAnalyticsDateWindowProvider.DateWindow window =
                        defaultAnalyticsDateWindowProvider.defaultWindow();
                startDate = window.startDate();
                endDate = window.endDate();
            } else if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("Provide both start_date and end_date together");
            }
            if (endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("end_date must be on or after start_date");
            }
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                DimLgdLocation tenantLevelLgd = dimLgdLocationRepository
                        .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(tenantId, 1)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No level-1 lgd_id found for tenant_id: " + tenantId));
                parentLgdId = tenantLevelLgd.getLgdId();
            }

            TenantDetailsResponse data = (parentDepartmentId != null)
                    ? tenantDetailsService.getTenantDetailsByParentDepartmentWithAggregatedMetrics(
                    tenantId, parentDepartmentId, startDate, endDate)
                    : tenantDetailsService.getTenantDetailsWithAggregatedMetrics(
                    tenantId, parentLgdId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<TenantDetailsResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<TenantDetailsResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed GET /tenant_data (tenantId={}, parentLgdId={}, parentDepartmentId={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<TenantDetailsResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/tenant_boundaries")
    @Operation(
            summary = "Get tenant boundary GeoJSON (parent + children), filtered by parent_lgd_id or parent_department_id",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Tenant boundary GeoJSON fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.TENANT_BOUNDARIES_SUCCESS)
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
    public ResponseEntity<ApiResponse<TenantBoundaryGeoJsonResponse>> getTenantBoundaryGeoJson(
            @RequestParam(name = "tenant_id", required = true) Integer tenantId,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId
    ) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                DimLgdLocation tenantLevelLgd = dimLgdLocationRepository
                        .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(tenantId, 1)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No level-1 lgd_id found for tenant_id: " + tenantId));
                parentLgdId = tenantLevelLgd.getLgdId();
            }

            TenantBoundaryGeoJsonResponse data = (parentDepartmentId != null)
                    ? tenantDetailsService.getTenantBoundaryGeoJsonByParentDepartment(tenantId, parentDepartmentId)
                    : tenantDetailsService.getTenantBoundaryGeoJson(tenantId, parentLgdId);

            return ResponseEntity.ok(ApiResponse.<TenantBoundaryGeoJsonResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<TenantBoundaryGeoJsonResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed GET /tenant_boundaries (tenantId={}, parentLgdId={}, parentDepartmentId={})",
                    tenantId, parentLgdId, parentDepartmentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<TenantBoundaryGeoJsonResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/tenant_performance_score")
    @Operation(
            summary = "Get average performance score for a tenant boundary (parent + children)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Performance score fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.TENANT_PERFORMANCE_SCORE_SUCCESS)
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
    public ResponseEntity<ApiResponse<TenantPerformanceScoreResponse>> getTenantPerformanceScore(
            @RequestParam(name = "tenant_id", required = true) Integer tenantId,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            if (startDate == null && endDate == null) {
                DefaultAnalyticsDateWindowProvider.DateWindow window =
                        defaultAnalyticsDateWindowProvider.defaultWindow();
                startDate = window.startDate();
                endDate = window.endDate();
            } else if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("Provide both start_date and end_date together");
            }
            if (endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("end_date must be on or after start_date");
            }
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                DimLgdLocation tenantLevelLgd = dimLgdLocationRepository
                        .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(tenantId, 1)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No level-1 lgd_id found for tenant_id: " + tenantId));
                parentLgdId = tenantLevelLgd.getLgdId();
            }

            TenantPerformanceScoreResponse data = (parentDepartmentId != null)
                    ? tenantDetailsService.getTenantPerformanceScoreByParentDepartment(
                    tenantId, parentDepartmentId, startDate, endDate)
                    : tenantDetailsService.getTenantPerformanceScoreByParentLgd(
                    tenantId, parentLgdId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<TenantPerformanceScoreResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<TenantPerformanceScoreResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed GET /tenant_performance_score (tenantId={}, parentLgdId={}, parentDepartmentId={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<TenantPerformanceScoreResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/schemes")
    @Operation(
            summary = "List schemes, optionally filtered by tenant",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Schemes fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEMES_SUCCESS)
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
    public ResponseEntity<ApiResponse<List<DimScheme>>> getSchemes(
            @RequestParam(required = false) Integer tenantId) {
        try {
            List<DimScheme> data = (tenantId != null)
                    ? dimSchemeRepository.findByTenantId(tenantId)
                    : dimSchemeRepository.findAll();
            return ResponseEntity.ok(ApiResponse.<List<DimScheme>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /schemes (tenantId={})", tenantId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<DimScheme>>builder()
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
}

