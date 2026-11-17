package org.arghyam.jalsoochak.analytics.controller.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.config.SwaggerExamples;
import org.arghyam.jalsoochak.analytics.dto.response.ApiResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OnboardedRegionsResponse;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phased rollout: which of a parent's children the dashboards may show. Not cached, so that
 * onboarding a district takes effect on the next request. Errors are answered by
 * {@code GlobalExceptionHandler}: 400 for a bad request, 404 (from the guard) for a parent that isn't
 * onboarded.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics - Tenants", description = "Tenant metadata, details, boundary GeoJSON, and performance scores")
public class AnalyticsRegionOnboardingController {

    private final RegionOnboardingService regionOnboardingService;

    @GetMapping("/onboarded-regions")
    @Operation(
            summary = "List a parent's onboarded children (phased rollout), by parent_lgd_id or parent_department_id",
            description = "phasedRollout=false means every region is onboarded, and onboardedChildIds is null.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "Onboarded children fetched successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = ApiResponse.class),
                                    examples = @ExampleObject(name = "success", value = SwaggerExamples.ONBOARDED_REGIONS_SUCCESS)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "400",
                            description = "Not exactly one parent, or, with phased rollout on, the tenant has no "
                                    + "such parent (with it off, the parent isn't looked up and the answer is 200)",
                            content = @Content(
                                    mediaType = "application/json",
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.ERROR_BAD_REQUEST)
                            )
                    ),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "404",
                            description = "The parent isn't onboarded",
                            content = @Content(
                                    mediaType = "application/json",
                                    examples = @ExampleObject(name = "failure", value = SwaggerExamples.ERROR_REGION_NOT_FOUND)
                            )
                    )
            }
    )
    public ResponseEntity<ApiResponse<OnboardedRegionsResponse>> getOnboardedRegions(
            @RequestParam(name = "tenant_id") Integer tenantId,
            @RequestParam(name = "parent_lgd_id", required = false) Integer parentLgdId,
            @RequestParam(name = "parent_department_id", required = false) Integer parentDepartmentId) {
        if ((parentLgdId == null) == (parentDepartmentId == null)) {
            throw new IllegalArgumentException("Provide exactly one of parent_lgd_id or parent_department_id");
        }
        OnboardedRegionsResponse data = parentLgdId != null
                ? regionOnboardingService.onboardedChildren(tenantId, RegionHierarchy.LGD, parentLgdId)
                : regionOnboardingService.onboardedChildren(tenantId, RegionHierarchy.DEPARTMENT, parentDepartmentId);

        return ResponseEntity.ok(ApiResponse.<OnboardedRegionsResponse>builder()
                .success(true)
                .data(data)
                .build());
    }
}
