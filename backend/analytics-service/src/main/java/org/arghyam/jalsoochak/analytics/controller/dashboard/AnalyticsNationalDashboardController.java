package org.arghyam.jalsoochak.analytics.controller.dashboard;

import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardBoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2BoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2MetricsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.helper.SingleTenantModeGuard;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The national dashboard: state-wise and district-wise aggregates and their map boundaries.
 *
 * <p>Every route is refused in single-tenant mode by {@link SingleTenantModeGuard}.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - National Dashboard", description = "National dashboard aggregates and map boundaries, state-wise and district-wise")
@Slf4j
public class AnalyticsNationalDashboardController {

    private final SchemeRegularityService schemeRegularityService;
    private final DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;
    private final SingleTenantModeGuard singleTenantModeGuard;

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
        singleTenantModeGuard.rejectIfSingleTenantMode("national/dashboard/boundary");
        try {
            return ResponseEntity.ok(ApiResponse.<NationalDashboardBoundaryResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardBoundariesForApi())
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /national/dashboard/boundary", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NationalDashboardBoundaryResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/national/dashboard/boundary/district")
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
        singleTenantModeGuard.rejectIfSingleTenantMode("national/dashboard/boundary/district");
        try {
            return ResponseEntity.ok(ApiResponse.<NationalDashboardLevel2BoundaryResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardLevel2BoundariesForApi())
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /national/dashboard/boundary/district", e);
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
        singleTenantModeGuard.rejectIfSingleTenantMode("national/dashboard");
        try {
            return ResponseEntity.ok(ApiResponse.<NationalDashboardResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardForApi(startDate, endDate))
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /national/dashboard (startDate={}, endDate={})", startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NationalDashboardResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/national/dashboard/district")
    @Operation(
            summary = "Get national dashboard aggregates at LGD level-2 (district) without repeating identity fields across arrays",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "National district dashboard fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.NATIONAL_DASHBOARD_LEVEL2_METRICS_SUCCESS))
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.GENERIC_FAILURE))
                    )
            }
    )
    public ResponseEntity<ApiResponse<NationalDashboardLevel2MetricsResponse>> getNationalDashboardDistrict(
            @RequestParam(name = "start_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        singleTenantModeGuard.rejectIfSingleTenantMode("national/dashboard/district");
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

            return ResponseEntity.ok(ApiResponse.<NationalDashboardLevel2MetricsResponse>builder()
                    .success(true)
                    .data(schemeRegularityService.getNationalDashboardLevel2MetricsForApi(startDate, endDate))
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<NationalDashboardLevel2MetricsResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<NationalDashboardLevel2MetricsResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}
