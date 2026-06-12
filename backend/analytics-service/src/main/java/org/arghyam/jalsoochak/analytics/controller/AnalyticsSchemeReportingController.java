package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ContinuousSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationPaginatedResponse;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.OperatorAttendanceQueryService;
import org.arghyam.jalsoochak.analytics.service.UserAlertTotalsService;
import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyPaginatedResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserAlertTotalsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Scheme Reporting", description = "Scheme dashboards, region reports (CSV/JSON), escalations, and scheme performance queries")
@Slf4j
public class AnalyticsSchemeReportingController {

    private static final String CSV_OUTPUT_FORMAT = "csv";

    private final FactSchemePerformanceRepository schemePerformanceRepository;
    private final SchemeRegularityService schemeRegularityService;
    private final EscalationQueryService escalationQueryService;
    private final AnomalyQueryService anomalyQueryService;
    private final OperatorAttendanceQueryService operatorAttendanceQueryService;
    private final UserAlertTotalsService userAlertTotalsService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;
    private final DimUserRepository dimUserRepository;
    private final FactEscalationRepository factEscalationRepository;
    private final DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    public record UpdateEscalationResolutionStatusRequest(Integer resolutionStatus) {
    }

    private Integer resolveUserIdByUuid(Integer tenantId, UUID userUuid) {
        return dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(tenantId, userUuid)
                .map(u -> u.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("No user found for uuid: " + userUuid));
    }

    @PutMapping("/escalations/status")
    @Operation(summary = "Update escalation resolution status (UUID-scoped)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateEscalationResolutionStatus(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "uuid") UUID userUuid,
            @RequestParam(name = "escalation_id", required = false) Long escalationId,
            @RequestParam(name = "correlation_id", required = false) String correlationId,
            @RequestBody UpdateEscalationResolutionStatusRequest request
    ) {
        try {
            if (request == null || request.resolutionStatus() == null) {
                throw new IllegalArgumentException("resolutionStatus is required");
            }
            boolean hasEscalationId = escalationId != null;
            boolean hasCorrelationId = correlationId != null && !correlationId.isBlank();
            if (hasEscalationId == hasCorrelationId) {
                throw new IllegalArgumentException("Provide exactly one of escalation_id or correlation_id");
            }

            Integer userId = resolveUserIdByUuid(tenantId, userUuid);

            java.util.Optional<FactEscalation> opt = hasEscalationId
                    ? factEscalationRepository.findByIdAndTenantIdAndUserId(escalationId, tenantId, userId)
                    : factEscalationRepository.findFirstByTenantIdAndUserIdAndCorrelationIdOrderByCreatedAtDesc(
                    tenantId, userId, correlationId.trim());

            FactEscalation escalation = opt.orElseThrow(() -> new IllegalArgumentException("Escalation not found for the given user/identifier"));

            escalation.setResolutionStatus(request.resolutionStatus());
            escalation.setUpdatedAt(LocalDateTime.now());
            FactEscalation saved = factEscalationRepository.save(escalation);

            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                    .success(true)
                    .data(Map.of(
                            "escalation_id", saved.getId(),
                            "resolution_status", saved.getResolutionStatus()
                    ))
                    .build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.<Map<String, Object>>builder()
                    .success(false)
                    .data(null)
                    .build());
        } catch (Exception ex) {
            log.error(
                    "Failed PUT /escalations/status (tenantId={}, uuid={}, escalationId={}, correlationId={})",
                    tenantId, userUuid, escalationId, correlationId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.<Map<String, Object>>builder()
                    .success(false)
                    .data(null)
                    .build());
        }
    }
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
            @RequestParam(name = "tenant_id") Integer tenantId,
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
                    ? schemeRegularityService.getSchemeStatusCountByLgd(tenantId, lgdId)
                    : schemeRegularityService.getSchemeStatusCountByDepartment(tenantId, departmentId);

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
            description = "A scheme is continuous when it has supplied water every day in the given date range (start_date..end_date) without a miss. Supply day is counted when confirmed_reading > 0 for that date. When list=false (default), only the count is computed/returned. When list=true, a paginated list is also returned.",
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
    public ResponseEntity<ApiResponse<ContinuousSchemesResponse>> getContinuousSchemesForUser(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "user_id") Integer userId,
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
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId,
            @RequestParam(name = "page_number", required = false, defaultValue = "1") Integer pageNumber,
            @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit) {
        try {
            if (parentLgdId != null && parentDepartmentId != null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id, not both");
            }
            if (parentLgdId == null && parentDepartmentId == null) {
                throw new IllegalArgumentException("Provide either parent_lgd_id or parent_department_id");
            }

            SchemeStatusAndTopReportingResponse data = (parentLgdId != null)
                    ? schemeRegularityService.getSchemeStatusAndTopReportingByLgd(tenantId, parentLgdId, startDate, endDate, pageNumber, limit)
                    : schemeRegularityService.getSchemeStatusAndTopReportingByDepartment(tenantId, parentDepartmentId, startDate, endDate, pageNumber, limit);

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
            @RequestParam(name = "tenant_id") Integer tenantId,
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
                    ? schemeRegularityService.getSchemeRegionReportByLgd(tenantId, parentLgdId, startDate, endDate, pageNumber, count)
                    : schemeRegularityService.getSchemeRegionReportByDepartment(tenantId, parentDepartmentId, startDate, endDate, pageNumber, count);

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
    @PreAuthorize("hasAnyAuthority('USER_TYPE_SECTION_OFFICER', 'USER_TYPE_SUB_DIVISIONAL_OFFICER')")
    public ResponseEntity<EscalationPaginatedResponse> getEscalationsPaginated(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "page_number", required = false, defaultValue = "1") Integer pageNumber,
            @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit,
            @RequestParam(name = "escalation_type", required = false) String escalationType,
            @RequestParam(name = "scheme_id", required = false) Integer schemeId,
            @RequestParam(name = "scheme_name", required = false) String schemeName,
            @RequestParam(name = "resolution_status", required = false) Integer resolutionStatus,
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

            AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                    authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
            log.info(
                    "Escalations request: extracted tenantId={}, userId={}, userUuid={}, page_number={}, limit={}, escalation_type={}, scheme_id={}, scheme_name_present={}, resolution_status={}, start_date={}, end_date={}",
                    userRef != null ? userRef.tenantId() : null,
                    userRef != null ? userRef.userId() : null,
                    userRef != null ? userRef.userUuid() : null,
                    pageNumber,
                    limit,
                    escalationType,
                    schemeId,
                    schemeName != null && !schemeName.isBlank(),
                    resolutionStatus,
                    startDate,
                    endDate
            );
            Integer tenantId = userRef.tenantId();
            if (tenantId == null || tenantId <= 0) {
                throw new IllegalArgumentException("tenant_id is required");
            }
            Integer userId = userRef.userId() != null
                    ? userRef.userId()
                    : resolveUserIdByUuid(tenantId, userRef.userUuid());

            PageRequest pageable = PageRequest.of(pageNumber - 1, limit, Sort.by("createdAt").descending());
            Page<EscalationListItemDto> page = escalationQueryService.getEscalations(
                    tenantId,
                    userId,
                    escalationType,
                    schemeId,
                    schemeName,
                    resolutionStatus,
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

    @GetMapping("/operator-attendance")
    @Operation(
            summary = "operator attendance for a user (by UUID) within an optional date range",
            description = "Defaults: end_date = today, start_date = end_date minus 30 days. Returns one entry per calendar day in range; days with no rows are attendance 0 (absent). Multiple schemes on the same day use max(attendance). Source: dim_operator_attendance_table, dim_user_table (uuid), dim_date_table.",
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

    @GetMapping("/anomalies")
    @Operation(
            summary = "Get paginated anomalies for schemes mapped to a user (via dim_user_scheme_mapping_table)",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Anomalies fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = AnomalyPaginatedResponse.class)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Bad request",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = AnomalyPaginatedResponse.class)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "500",
                            description = "Unexpected error",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = AnomalyPaginatedResponse.class)
                            )
                    )
            }
    )
    @PreAuthorize("hasAnyAuthority('USER_TYPE_SECTION_OFFICER', 'USER_TYPE_SUB_DIVISIONAL_OFFICER')")
    public ResponseEntity<AnomalyPaginatedResponse> getAnomalies(
            JwtAuthenticationToken authentication,
            @RequestParam(name = "page_number", required = false, defaultValue = "1") Integer pageNumber,
            @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "anomaly_type", required = false) String anomalyType,
            @RequestParam(name = "scheme_name", required = false) String schemeName,
            @RequestParam(name = "status", required = false) Integer status
    ) {
        try {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("page_number must be >= 1");
            }
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be >= 1");
            }

            AnalyticsControllerHelper.AuthenticatedUserRef userRef =
                    authenticatedRequestContextService.extractAuthenticatedUserRef(authentication);
            Integer tenantId = userRef.tenantId();
            if (tenantId == null || tenantId <= 0) {
                throw new IllegalArgumentException("tenant_id is required");
            }
            Integer mappedUserId = userRef.userId() != null
                    ? userRef.userId()
                    : resolveUserIdByUuid(tenantId, userRef.userUuid());

            PageRequest pageable = PageRequest.of(pageNumber - 1, limit, Sort.by("createdAt").descending());
            Page<AnomalyListItemDto> page = anomalyQueryService.getAnomaliesForUserSchemes(
                    tenantId,
                    mappedUserId,
                    startDate,
                    endDate,
                    anomalyType,
                    schemeName,
                    status,
                    pageable
            );

            return ResponseEntity.ok(AnomalyPaginatedResponse.builder()
                    .success(true)
                    .page(pageNumber)
                    .limit(limit)
                    .totalCount(page.getTotalElements())
                    .anomalies(page.getContent())
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(AnomalyPaginatedResponse.builder()
                    .success(false)
                    .page(pageNumber)
                    .limit(limit)
                    .totalCount(0)
                    .anomalies(List.of())
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(AnomalyPaginatedResponse.builder()
                    .success(false)
                    .page(pageNumber)
                    .limit(limit)
                    .totalCount(0)
                    .anomalies(List.of())
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
    public ResponseEntity<ApiResponse<UserAlertTotalsResponse>> getUserAlertTotals(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "user_id") Integer userId,
            @RequestParam(name = "start_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "end_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        try {
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
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(required = false) Integer schemeId) {
        try {
            List<FactSchemePerformance> data;
            if (schemeId != null) {
                data = schemePerformanceRepository.findByTenantIdAndSchemeId(tenantId, schemeId);
            } else {
                data = schemePerformanceRepository.findByTenantId(tenantId);
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

