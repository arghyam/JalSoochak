package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardBoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2BoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicNationalSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.SingleTenantModeAccessException;
import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
@Tag(name = "Analytics - Water Supply & National Dashboard", description = "Water supply metrics, national dashboard aggregates, and date dimension utilities")
public class AnalyticsWaterSupplyNationalController {

    private final SchemeRegularityService schemeRegularityService;
    private final DateDimensionService dateDimensionService;

    @Value("${analytics.single-tenant-mode:false}")
    private boolean singleTenantMode;

    private void rejectIfSingleTenantMode(String apiName) {
        if (!singleTenantMode) {
            return;
        }
        String message = "API '" + apiName + "' cannot be accessed when single-tenant mode is enabled";
        log.warn(message);
        throw new SingleTenantModeAccessException(message);
    }

    @GetMapping("/national/dashboard/boundary")
    @Operation(
            summary = "Get state-wise boundaries for the national dashboard map (GeoJSON per tenant)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "State boundaries fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.NATIONAL_DASHBOARD_BOUNDARY_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<NationalDashboardBoundaryResponse>> getNationalDashboardBoundaries() {
        rejectIfSingleTenantMode("national/dashboard/boundary");
        try {
            return ResponseEntity.ok(ApiResponse.<NationalDashboardBoundaryResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardBoundariesForApi())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NationalDashboardBoundaryResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/national/dashboard/boundary/level2")
    @Operation(
            summary = "Get national outer boundary + LGD level-2 boundaries for the national dashboard map",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "National + level-2 boundaries fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.NATIONAL_DASHBOARD_LEVEL2_BOUNDARY_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<NationalDashboardLevel2BoundaryResponse>> getNationalDashboardLevel2Boundaries() {
        rejectIfSingleTenantMode("national/dashboard/boundary/level2");
        try {
            return ResponseEntity.ok(ApiResponse.<NationalDashboardLevel2BoundaryResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardLevel2BoundariesForApi())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NationalDashboardLevel2BoundaryResponse>builder()
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
        rejectIfSingleTenantMode("national/dashboard");
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
        rejectIfSingleTenantMode("scheme-regularity/periodic/national");
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

//     @PostMapping("/date-dimension")
//     @Operation(
//             summary = "Pre-populate the date dimension for a given range",
//             responses = {
//                     @io.swagger.v3.oas.annotations.responses.ApiResponse(
//                             responseCode = "200",
//                             description = "Date dimension populated successfully",
//                             content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
//                                     examples = @ExampleObject(name = "success", value = SwaggerExamples.DATE_DIMENSION_POPULATE_SUCCESS))
//                     ),
//                     @io.swagger.v3.oas.annotations.responses.ApiResponse(
//                             responseCode = "500",
//                             description = "Unexpected error",
//                             content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
//                                     examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
//                     )
//             }
//     )
//     public ResponseEntity<ApiResponse<String>> populateDateDimension(
//             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
//             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
//         try {
//             dateDimensionService.populateDateRange(startDate, endDate);
//             return ResponseEntity.ok(ApiResponse.<String>builder()
//                     .success(true)
//                     .data("Date dimension populated from " + startDate + " to " + endDate)
//                     .build());
//         } catch (Exception e) {
//             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<String>builder()
//                     .success(false)
//                     .data(null)
//                     .build());
//         }
//     }
}

