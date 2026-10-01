package org.arghyam.jalsoochak.analytics.controller.water;

import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
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

/**
 * Outage reason distributions, for an area, over time and for the signed-in officer.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Outages", description = "Outage reason distributions, per area, periodic, and for the signed-in officer")
@Slf4j
public class AnalyticsOutageController {

    private final SchemeRegularityService schemeRegularityService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;

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
}
