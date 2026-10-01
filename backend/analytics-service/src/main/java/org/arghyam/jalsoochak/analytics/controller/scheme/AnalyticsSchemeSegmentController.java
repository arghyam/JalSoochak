package org.arghyam.jalsoochak.analytics.controller.scheme;

import org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ContinuousSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
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

/**
 * Critical and continuously supplied schemes, for an area and for the signed-in officer.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Critical & Continuous Schemes", description = "Critical and continuous scheme counts and lists, per area and for the signed-in officer")
public class AnalyticsSchemeSegmentController {

    private final SchemeRegularityService schemeRegularityService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;
    private final DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @GetMapping("/critical-schemes")
    @Operation(
            summary = "Get critical scheme count (and optionally list) for an LGD or department area",
            description = "A scheme is critical when it has not supplied water in the last N days (N = analytics.scheduler.scheme-status.critical-after-days). When list=false (default), only the count is computed/returned. When list=true, a paginated list is also returned.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Critical schemes fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = {
                                            @ExampleObject(name = "count_only", value = SwaggerExamples.CRITICAL_SCHEMES_COUNT_SUCCESS),
                                            @ExampleObject(name = "with_list", value = SwaggerExamples.CRITICAL_SCHEMES_LIST_SUCCESS)
                                    }
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
    public ResponseEntity<ApiResponse<CriticalSchemesResponse>> getCriticalSchemes(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "lgd_id", required = false) Integer lgdId,
            @RequestParam(name = "department_id", required = false) Integer departmentId,
            @RequestParam(name = "list", required = false, defaultValue = "false") boolean list,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        try {
            if (lgdId != null && departmentId != null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id, not both");
            }
            if (lgdId == null && departmentId == null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id");
            }
            if (list) {
                if (page != null && page < 1) {
                    throw new IllegalArgumentException("page must be >= 1");
                }
                if (limit != null && limit < 1) {
                    throw new IllegalArgumentException("limit must be >= 1");
                }
            }

            CriticalSchemesResponse data = (lgdId != null)
                    ? schemeRegularityService.getCriticalSchemesByLgd(tenantId, lgdId, list, page, limit)
                    : schemeRegularityService.getCriticalSchemesByDepartment(tenantId, departmentId, list, page, limit);

            return ResponseEntity.ok(ApiResponse.<CriticalSchemesResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<CriticalSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<CriticalSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/critical-schemes/user")
    @Operation(
            summary = "Get critical scheme count (and optionally list) for schemes mapped to the authenticated user",
            description = "Same as /critical-schemes, but scoped to schemes mapped to the authenticated user via dim_user_scheme_mapping_table. tenant_id and user identity are extracted from the JWT. When list=false (default), only the count is computed/returned. When list=true, a paginated list is also returned.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Critical schemes (user) fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = {
                                            @ExampleObject(name = "count_only", value = SwaggerExamples.CRITICAL_SCHEMES_USER_COUNT_SUCCESS),
                                            @ExampleObject(name = "with_list", value = SwaggerExamples.CRITICAL_SCHEMES_USER_LIST_SUCCESS)
                                    }
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
    public ResponseEntity<ApiResponse<CriticalSchemesResponse>> getCriticalSchemesForUser(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "list", required = false, defaultValue = "false") boolean list,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        try {
            if (list) {
                if (page != null && page < 1) {
                    throw new IllegalArgumentException("page must be >= 1");
                }
                if (limit != null && limit < 1) {
                    throw new IllegalArgumentException("limit must be >= 1");
                }
            }

            AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                    authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
            if (userRef == null || userRef.tenantId() == null || userRef.tenantId() <= 0) {
                throw new IllegalArgumentException("tenant_id is required");
            }

            CriticalSchemesResponse data = userRef.userId() != null
                    ? schemeRegularityService.getCriticalSchemesByUser(userRef.tenantId(), userRef.userId(), list, page, limit)
                    : schemeRegularityService.getCriticalSchemesByUserUuid(userRef.tenantId(), userRef.userUuid(), list, page, limit);

            return ResponseEntity.ok(ApiResponse.<CriticalSchemesResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<CriticalSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<CriticalSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/continuous-schemes")
    @Operation(
            summary = "Get continuous scheme count (and optionally list) for an LGD or department area",
            description = "A scheme is continuous when it reported on at least one day in the given date range (start_date..end_date). A day counts as reported when the scheme has any meter reading (including a 0-supply reading), an image-rejected submission, or a submission attempt on that date. Applies identically to LGD and department scopes. When list=false (default), only the count is computed/returned. When list=true, a paginated list is also returned.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Continuous schemes fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = {
                                            @ExampleObject(name = "count_only", value = SwaggerExamples.CONTINUOUS_SCHEMES_COUNT_SUCCESS),
                                            @ExampleObject(name = "with_list", value = SwaggerExamples.CONTINUOUS_SCHEMES_LIST_SUCCESS)
                                    }
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
    public ResponseEntity<ApiResponse<ContinuousSchemesResponse>> getContinuousSchemes(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "lgd_id", required = false) Integer lgdId,
            @RequestParam(name = "department_id", required = false) Integer departmentId,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "list", required = false, defaultValue = "false") boolean list,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        try {
            if (startDate == null && endDate == null) {
                DefaultAnalyticsDateWindowProvider.DateWindow window =
                        defaultAnalyticsDateWindowProvider.defaultWindow();
                startDate = window.startDate();
                endDate = window.endDate();
            } else if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("Provide both start_date and end_date together");
            }
            if (lgdId != null && departmentId != null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id, not both");
            }
            if (lgdId == null && departmentId == null) {
                throw new IllegalArgumentException("Provide either lgd_id or department_id");
            }
            if (startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("start_date must be on or before end_date");
            }
            if (list) {
                if (page != null && page < 1) {
                    throw new IllegalArgumentException("page must be >= 1");
                }
                if (limit != null && limit < 1) {
                    throw new IllegalArgumentException("limit must be >= 1");
                }
            }

            ContinuousSchemesResponse data = (lgdId != null)
                    ? schemeRegularityService.getContinuousSchemesByLgd(tenantId, lgdId, startDate, endDate, list, page, limit)
                    : schemeRegularityService.getContinuousSchemesByDepartment(tenantId, departmentId, startDate, endDate, list, page, limit);

            return ResponseEntity.ok(ApiResponse.<ContinuousSchemesResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<ContinuousSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<ContinuousSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }

    @GetMapping("/continuous-schemes/user")
    @Operation(
            summary = "Get continuous scheme count (and optionally list) for schemes mapped to a user",
            description = "Same as /continuous-schemes, but scoped to schemes mapped to the given user via dim_user_scheme_mapping_table.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Continuous schemes (user) fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = {
                                            @ExampleObject(name = "count_only", value = SwaggerExamples.CONTINUOUS_SCHEMES_USER_COUNT_SUCCESS),
                                            @ExampleObject(name = "with_list", value = SwaggerExamples.CONTINUOUS_SCHEMES_USER_LIST_SUCCESS)
                                    }
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
    public ResponseEntity<ApiResponse<ContinuousSchemesResponse>> getContinuousSchemesForUser(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "list", required = false, defaultValue = "false") boolean list,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        try {
            if (startDate == null && endDate == null) {
                DefaultAnalyticsDateWindowProvider.DateWindow window =
                        defaultAnalyticsDateWindowProvider.defaultWindow();
                startDate = window.startDate();
                endDate = window.endDate();
            } else if (startDate == null || endDate == null) {
                throw new IllegalArgumentException("Provide both start_date and end_date together");
            }
            if (startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("start_date must be on or before end_date");
            }
            if (list) {
                if (page != null && page < 1) {
                    throw new IllegalArgumentException("page must be >= 1");
                }
                if (limit != null && limit < 1) {
                    throw new IllegalArgumentException("limit must be >= 1");
                }
            }

            // Identity comes from the token, never from the request. Taking tenant_id and user_id
            // as query parameters let any authenticated caller read another officer's schemes --
            // and, because tenant_id was equally free, another tenant's.
            AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                    authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
            Integer tenantId = userRef == null ? null : userRef.tenantId();
            if (tenantId == null || tenantId <= 0) {
                throw new IllegalArgumentException("tenant_id is required");
            }
            Integer userId = userRef.userId() != null
                    ? userRef.userId()
                    : authenticatedRequestContextService.resolveUserIdByUuid(tenantId, userRef.userUuid());

            ContinuousSchemesResponse data =
                    schemeRegularityService.getContinuousSchemesByUser(tenantId, userId, startDate, endDate, list, page, limit);

            return ResponseEntity.ok(ApiResponse.<ContinuousSchemesResponse>builder()
                    .success(true)
                    .data(data)
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.<ContinuousSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<ContinuousSchemesResponse>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
}
