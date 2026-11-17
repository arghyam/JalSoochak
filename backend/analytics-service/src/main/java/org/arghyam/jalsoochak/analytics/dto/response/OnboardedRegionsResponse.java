package org.arghyam.jalsoochak.analytics.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "OnboardedRegionsResponse",
        description = "A parent's children that are reachable on the dashboards during a phased rollout")
public class OnboardedRegionsResponse {

    private Integer tenantId;

    @Schema(description = "Set when the parent is an LGD region, otherwise null")
    private Integer parentLgdId;

    @Schema(description = "Set when the parent is a department region, otherwise null")
    private Integer parentDepartmentId;

    @Schema(description = "Whether PHASED_ROLLOUT_ENABLED is on. When false, every region is onboarded")
    private boolean phasedRollout;

    @Schema(description = "The onboarded children, in ascending id order. Null when phasedRollout is false",
            nullable = true)
    private List<Integer> onboardedChildIds;
}
