package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantBoundaryGeoJsonChildRegion {

    private Integer lgdId;
    private Integer departmentId;
    private Integer parentLgdId;
    private Integer parentDepartmentId;
    private Integer lgdLevel;

    private String title;
    private String lgdCode;

    private String boundaryGeoJson;
}

