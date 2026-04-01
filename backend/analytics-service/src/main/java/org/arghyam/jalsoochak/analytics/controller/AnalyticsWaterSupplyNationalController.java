package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicNationalSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.enums.WaterSupplyScope;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Water Supply & National Dashboard", description = "Water supply metrics, national dashboard aggregates, and date dimension utilities")
public class AnalyticsWaterSupplyNationalController {

    private final SchemeRegularityService schemeRegularityService;
    private final DateDimensionService dateDimensionService;

    // This endpoint is used to get the average water supply per region in liters/household;
    // tenant_id optional for nation-level state aggregates
    @GetMapping("/water-supply/average-per-region")
    @Operation(
            summary = "Get average water supply per region in liters/household with response scope (current|child)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Average water supply fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.WATER_SUPPLY_AVERAGE_PER_REGION_SUCCESS))
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
    public ResponseEntity<ApiResponse<AverageWaterSupplyResponse>> getAverageWaterSupplyPerCurrentRegion(
            @RequestParam(name = "tenant_id", required = false) Integer tenantId,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @Parameter(
                    description = "Response scope",
                    required = false,
                    schema = @Schema(type = "string", allowableValues = {"current", "child"}, defaultValue = "current"))
            @RequestParam(name = "scope", defaultValue = "current") String scope,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            WaterSupplyScope waterSupplyScope = WaterSupplyScope.fromValue(scope);
            AverageWaterSupplyResponse data;

            if (waterSupplyScope == WaterSupplyScope.CURRENT) {
                if (tenantId == null) {
                    throw new IllegalArgumentException("tenant_id is required when scope=current");
                }
                if (parentLgdId != null || parentDepartmentId != null) {
                    throw new IllegalArgumentException("parent_lgd_id or parent_department_id is not supported when scope=current");
                }
                data = schemeRegularityService.getAverageWaterSupplyPerCurrentRegionForCurrentScope(tenantId, startDate, endDate);
            } else if (tenantId == null) {
                if (parentLgdId != null || parentDepartmentId != null) {
                    throw new IllegalArgumentException("tenant_id is required when parent_lgd_id or parent_department_id is provided");
                }
                data = schemeRegularityService.getAverageWaterSupplyPerNationForChildScope(startDate, endDate);
            } else if (parentLgdId != null) {
                data = schemeRegularityService.getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(
                        tenantId, parentLgdId, startDate, endDate);
            } else if (parentDepartmentId != null) {
                data = schemeRegularityService.getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(
                        tenantId, parentDepartmentId, startDate, endDate);
            } else {
                throw new IllegalArgumentException(
                        "Provide parent_lgd_id or parent_department_id when scope=child and tenant_id is provided");
            }

            return ResponseEntity.ok(ApiResponse.<AverageWaterSupplyResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<AverageWaterSupplyResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<AverageWaterSupplyResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/national/dashboard")
    @Operation(
            summary = "Get national dashboard aggregates with state-wise metrics and overall outage distribution",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "National dashboard fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.NATIONAL_DASHBOARD_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<NationalDashboardResponse>> getNationalDashboard(
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            return ResponseEntity.ok(ApiResponse.<NationalDashboardResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardForApi(startDate, endDate))
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NationalDashboardResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/scheme-regularity/periodic/national")
    @Operation(
            summary = "Get periodic average scheme regularity for national level (all tenants)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "National periodic scheme regularity fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.SCHEME_REGULARITY_PERIODIC_NATIONAL_SUCCESS))
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
    public ResponseEntity<ApiResponse<PeriodicNationalSchemeRegularityResponse>> getPeriodicNationalSchemeRegularity(
            @RequestParam(name = "start_date")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(
                    description = "Time aggregation scale",
                    required = true,
                    schema = @Schema(type = "string", allowableValues = {"day", "week", "month"}))
                    @RequestParam(name = "scale") String scale) {
        try {
            PeriodScale periodScale = PeriodScale.fromValue(scale);
            return ResponseEntity.ok(ApiResponse.<PeriodicNationalSchemeRegularityResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getPeriodicSchemeRegularityForNationForApi(
                            startDate, endDate, periodScale))
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<PeriodicNationalSchemeRegularityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<PeriodicNationalSchemeRegularityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @PostMapping("/date-dimension/populate")
    @Operation(
            summary = "Pre-populate the date dimension for a given range",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Date dimension populated successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.DATE_DIMENSION_POPULATE_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<String>> populateDateDimension(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            dateDimensionService.populateDateRange(startDate, endDate);
            return ResponseEntity.ok(ApiResponse.<String>builder()
                    .success(true)
                    .data("Date dimension populated from " + startDate + " to " + endDate)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<String>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}

