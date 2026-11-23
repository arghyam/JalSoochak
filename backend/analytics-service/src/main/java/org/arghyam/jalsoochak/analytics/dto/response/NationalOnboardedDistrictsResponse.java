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
@Schema(name = "NationalOnboardedDistrictsResponse",
        description = "Every tenant's districts that are reachable on the national map during a phased rollout")
public class NationalOnboardedDistrictsResponse {

    @Schema(description = "Whether PHASED_ROLLOUT_ENABLED is on. When false, every district is onboarded")
    private boolean phasedRollout;

    @Schema(description = "The tenants with at least one onboarded district, in ascending tenant_id order. "
            + "A tenant that isn't listed has none. Null when phasedRollout is false",
            nullable = true)
    private List<TenantOnboardedDistricts> tenants;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "TenantOnboardedDistricts")
    public static class TenantOnboardedDistricts {

        private Integer tenantId;

        @Schema(description = "The tenant's onboarded districts (lgd_id), in ascending order")
        private List<Integer> onboardedDistrictIds;
    }
}
