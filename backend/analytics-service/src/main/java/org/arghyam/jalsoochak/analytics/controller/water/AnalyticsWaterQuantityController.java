package org.arghyam.jalsoochak.analytics.controller.water;

import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.WaterSupplyScope;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Average water supply per region, and region-wise and periodic water quantity.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Water Quantity", description = "Average water supply per region and region-wise and periodic water quantity metrics")
@Slf4j
public class AnalyticsWaterQuantityController {

    private final SchemeRegularityService schemeRegularityService;

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
            @RequestParam(name = "tenant_id", required = true) Integer tenantId,
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
            log.error(
                    "Failed /water-supply/average-per-region (tenantId={}, parentLgdId={}, parentDepartmentId={}, scope={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, scope, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<AverageWaterSupplyResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/water-quantity/region-wise")
    @Operation(
            summary = "Get child region-wise eWater quantity and household count by parent LGD or parent department",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Region-wise water quantity fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.WATER_QUANTITY_REGION_WISE_SUCCESS))
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
    public ResponseEntity<ApiResponse<RegionWiseWaterQuantityResponse>> getWaterQuantityRegionWise(
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

            RegionWiseWaterQuantityResponse data = (parentLgdId != null)
                    ? schemeRegularityService.getRegionWiseWaterQuantityByLgd(tenantId, parentLgdId, startDate, endDate)
                    : schemeRegularityService.getRegionWiseWaterQuantityByDepartment(tenantId, parentDepartmentId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<RegionWiseWaterQuantityResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<RegionWiseWaterQuantityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (InvalidDataAccessApiUsageException e) {
            // Spring wraps IllegalArgumentException thrown from @Repository beans.
            // Treat "bad input" the same way as explicit IllegalArgumentException.
            return ResponseEntity.badRequest().body(ApiResponse.<RegionWiseWaterQuantityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error("Failed water-quantity/region-wise (tenantId={}, parentLgdId={}, parentDepartmentId={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<RegionWiseWaterQuantityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/water-quantity/periodic")
    @Operation(
            summary = "Get periodic average water quantity and household count for an LGD ID or department",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Periodic water quantity fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.WATER_QUANTITY_PERIODIC_SUCCESS))
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
    public ResponseEntity<ApiResponse<PeriodicWaterQuantityResponse>> getPeriodicWaterQuantity(
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
            PeriodicWaterQuantityResponse data = (lgdId != null)
                    ? schemeRegularityService.getPeriodicWaterQuantityByLgdId(lgdId, startDate, endDate, periodScale)
                    : schemeRegularityService.getPeriodicWaterQuantityByDepartment(departmentId, startDate, endDate, periodScale);

            return ResponseEntity.ok(ApiResponse.<PeriodicWaterQuantityResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<PeriodicWaterQuantityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed /water-quantity/periodic (lgdId={}, departmentId={}, startDate={}, endDate={}, scale={})",
                    lgdId, departmentId, startDate, endDate, scale, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<PeriodicWaterQuantityResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}
