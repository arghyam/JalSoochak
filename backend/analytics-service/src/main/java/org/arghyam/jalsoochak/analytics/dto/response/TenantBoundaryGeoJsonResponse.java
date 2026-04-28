package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantBoundaryGeoJsonResponse {

    private Integer tenantId;
    private String stateCode;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private Integer childBoundaryCount;
    private Integer childRegionCount;

    private String parentBoundaryGeoJson;

    private List<TenantBoundaryGeoJsonChildRegion> childRegions;
}

