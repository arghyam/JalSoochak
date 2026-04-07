package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDetailsResponse {

    private Integer tenantId;
    private String stateCode;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private Integer childBoundaryCount;
    private String boundaryGeoJson;
    private BigDecimal averageSchemeRegularity;
    private BigDecimal readingSubmissionRate;
    private BigDecimal averagePerformanceScore;
    private List<ChildRegionDetails> childRegions;
}
