package org.arghyam.jalsoochak.analytics.controller.incident;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationPaginatedResponse;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationResolutionStatusDto;
import org.arghyam.jalsoochak.analytics.dto.response.StatusItemDto;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import java.util.Arrays;
import java.util.List;

/**
 * Escalations for the signed-in officer, and the escalation statuses to filter them by.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Escalations", description = "Escalations for the signed-in officer and the escalation status filter list")
@Slf4j
public class AnalyticsEscalationController {

    private final EscalationQueryService escalationQueryService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;

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
                    : authenticatedRequestContextService.resolveUserIdByUuid(tenantId, userRef.userUuid());

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

    @GetMapping("/escalations/statuses")
    @Operation(
            summary = "List escalation statuses",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Escalation statuses fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.ESCALATION_STATUSES_SUCCESS)
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
    public ResponseEntity<ApiResponse<List<StatusItemDto>>> getEscalationStatuses() {
        try {
            List<StatusItemDto> statuses = Arrays.stream(EscalationResolutionStatusDto.values())
                    .map(status -> new StatusItemDto(status.getCode(), status.getLabel()))
                    .toList();
            return ResponseEntity.ok(ApiResponse.<List<StatusItemDto>>builder()
                    .success(true)
                    .data(statuses)
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /api/v1/analytics/escalations/statuses", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<List<StatusItemDto>>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }
}
