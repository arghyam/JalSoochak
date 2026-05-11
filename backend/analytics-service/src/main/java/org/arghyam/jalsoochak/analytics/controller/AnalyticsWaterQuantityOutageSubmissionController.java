package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.WaterSupplyScope;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Water Quantity, Outages & Submission", description = "Water quantity metrics, outage/non-submission distributions, and submission status aggregates")
@Slf4j
public class AnalyticsWaterQuantityOutageSubmissionController {

    private final SchemeRegularityService schemeRegularityService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;

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

    @GetMapping("/outage-reasons")
    @Operation(
            summary = "Get outage reason wise scheme count for an LGD or department area",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Outage reasons fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.OUTAGE_REASONS_SUCCESS))
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
    public ResponseEntity<ApiResponse<OutageReasonSchemeCountResponse>> getOutageReasonWiseSchemeCount(
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

            OutageReasonSchemeCountResponse data = (parentLgdId != null)
                    ? schemeRegularityService.getOutageReasonSchemeCountByLgd(tenantId, parentLgdId, startDate, endDate)
                    : schemeRegularityService.getOutageReasonSchemeCountByDepartment(tenantId, parentDepartmentId, startDate, endDate);

            return ResponseEntity.ok(ApiResponse.<OutageReasonSchemeCountResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<OutageReasonSchemeCountResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed /outage-reasons (tenantId={}, parentLgdId={}, parentDepartmentId={}, startDate={}, endDate={})",
                    tenantId, parentLgdId, parentDepartmentId, startDate, endDate, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<OutageReasonSchemeCountResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/outage-reasons/periodic")
    @Operation(
            summary = "Get periodic outage reason wise distinct scheme counts for an LGD ID or department (no child regions)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Periodic outage reasons fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.OUTAGE_REASONS_PERIODIC_SUCCESS))
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
    public ResponseEntity<ApiResponse<PeriodicOutageReasonSchemeCountResponse>> getPeriodicOutageReasonWiseSchemeCount(
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
            PeriodicOutageReasonSchemeCountResponse data = (lgdId != null)
                    ? schemeRegularityService.getPeriodicOutageReasonSchemeCountByLgdId(tenantId, lgdId, startDate, endDate, periodScale)
                    : schemeRegularityService.getPeriodicOutageReasonSchemeCountByDepartment(tenantId, departmentId, startDate, endDate, periodScale);

            return ResponseEntity.ok(ApiResponse.<PeriodicOutageReasonSchemeCountResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<PeriodicOutageReasonSchemeCountResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            log.error(
                    "Failed /outage-reasons/periodic (tenantId={}, lgdId={}, departmentId={}, startDate={}, endDate={}, scale={})",
                    tenantId, lgdId, departmentId, startDate, endDate, scale, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<PeriodicOutageReasonSchemeCountResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/outage-reasons/user")
    @Operation(
            summary = "Get outage reason wise scheme count for a user",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Outage reasons (user) fetched successfully",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.OUTAGE_REASONS_USER_SUCCESS))
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
    public ResponseEntity<ApiResponse<UserOutageReasonSchemeCountResponse>> getOutageReasonWiseSchemeCountByUser(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
        UserOutageReasonSchemeCountResponse data = userRef.userId() != null
                ? schemeRegularityService.getOutageReasonSchemeCountByUser(userRef.tenantId(), userRef.userId(), startDate, endDate)
                : schemeRegularityService.getOutageReasonSchemeCountByUserUuid(userRef.tenantId(), userRef.userUuid(), startDate, endDate);
        return ResponseEntity.ok(ApiResponse.<UserOutageReasonSchemeCountResponse>builder()
                .success(true)
                .data(data)
                .build());
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
