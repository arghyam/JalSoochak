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
public class TenantPerformanceScoreResponse {

    private Integer tenantId;
    private String stateCode;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private BigDecimal averagePerformanceScore;
    private List<TenantPerformanceChildRegionDetails> childRegions;
}

