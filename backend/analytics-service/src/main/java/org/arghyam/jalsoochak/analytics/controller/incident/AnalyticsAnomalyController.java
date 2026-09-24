package org.arghyam.jalsoochak.analytics.controller.incident;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyPaginatedResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyStatusDto;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.StatusItemDto;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
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
 * Anomalies on the signed-in officer's schemes, and the anomaly statuses to filter them by.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Anomalies", description = "Anomalies for the signed-in officer's schemes and the anomaly status filter list")
@Slf4j
public class AnalyticsAnomalyController {

    private final AnomalyQueryService anomalyQueryService;
    private final AuthenticatedRequestContextService authenticatedRequestContextService;

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
                    : authenticatedRequestContextService.resolveUserIdByUuid(tenantId, userRef.userUuid());

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

    @GetMapping("/anomalies/statuses")
    @Operation(
            summary = "List anomaly statuses",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Anomaly statuses fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.ANOMALY_STATUSES_SUCCESS)
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
    public ResponseEntity<ApiResponse<List<StatusItemDto>>> getAnomalyStatuses() {
        try {
            List<StatusItemDto> statuses = Arrays.stream(AnomalyStatusDto.values())
                    .map(status -> new StatusItemDto(status.getCode(), status.getLabel()))
                    .toList();
            return ResponseEntity.ok(ApiResponse.<List<StatusItemDto>>builder()
                    .success(true)
                    .data(statuses)
                    .build());
        } catch (Exception e) {
            log.error("Failed GET /api/v1/analytics/anomalies/statuses", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.<List<StatusItemDto>>builder()
                            .success(false)
                            .data(null)
                            .build());
        }
    }
}
