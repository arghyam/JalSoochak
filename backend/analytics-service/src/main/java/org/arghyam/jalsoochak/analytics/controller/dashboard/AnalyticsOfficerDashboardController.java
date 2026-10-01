package org.arghyam.jalsoochak.analytics.controller.dashboard;

import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.OperatorAttendanceQueryService;
import org.arghyam.jalsoochak.analytics.service.UserAlertTotalsService;
import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.UserAlertTotalsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
import java.util.List;
import java.util.UUID;

/**
 * The signed-in officer's alert totals, and a pump operator's day-wise attendance.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Officer Dashboard", description = "Alert totals for the signed-in officer and day-wise operator attendance")
public class AnalyticsOfficerDashboardController {

    private final OperatorAttendanceQueryService operatorAttendanceQueryService;
    private final UserAlertTotalsService userAlertTotalsService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;

    @GetMapping("/operator-attendance")
    @Operation(
            summary = "operator attendance for a user (by UUID) within an optional date range",
            description = "Defaults: end_date = today, start_date = end_date minus 30 days. Returns one entry per calendar day in range; days with no rows are attendance 0 (absent). Multiple schemes on the same day use max(attendance). Source: fact_operator_attendance_table, dim_user_table (uuid), dim_date_table.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Attendance rows returned (may be empty)",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request (e.g. start_date after end_date)",
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
    public ResponseEntity<ApiResponse<List<OperatorAttendanceDayItemDto>>> getOperatorAttendanceDayWise(
            @RequestParam(name = "uuid") UUID userUuid,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
            List<OperatorAttendanceDayItemDto> data = operatorAttendanceQueryService.getDayWiseAttendance(
                    userUuid, startDate, endDate);
            return ResponseEntity.ok(ApiResponse.<List<OperatorAttendanceDayItemDto>>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<List<OperatorAttendanceDayItemDto>>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<List<OperatorAttendanceDayItemDto>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/officer/dashboard")
    @Operation(
            summary = "Get totals for escalations, anomalies, mapped schemes and water supplied for a user",
            description = "Escalations are counted where fact_escalation_table.user_id = user_id. Anomalies/scheme/water totals are scoped to schemes mapped to the user via dim_user_scheme_mapping_table. Date range defaults: end_date=today, start_date=end_date-30 days.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Totals fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.OFFICER_DASHBOARD_TOTALS_SUCCESS)
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
    @PreAuthorize("hasAnyAuthority('USER_TYPE_SECTION_OFFICER', 'USER_TYPE_SUB_DIVISIONAL_OFFICER')")
    public ResponseEntity<ApiResponse<UserAlertTotalsResponse>> getUserAlertTotals(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
            // As with /continuous-schemes/user: the officer whose totals these are is the one
            // holding the token, not whoever the caller names in a query string.
            AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                    authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
            Integer tenantId = userRef == null ? null : userRef.tenantId();
            if (tenantId == null || tenantId <= 0) {
                throw new IllegalArgumentException("tenant_id is required");
            }
            Integer userId = userRef.userId() != null
                    ? userRef.userId()
                    : authenticatedRequestContextService.resolveUserIdByUuid(tenantId, userRef.userUuid());

            UserAlertTotalsResponse data = userAlertTotalsService.getTotals(tenantId, userId, startDate, endDate);
            return ResponseEntity.ok(ApiResponse.<UserAlertTotalsResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<UserAlertTotalsResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<UserAlertTotalsResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}
