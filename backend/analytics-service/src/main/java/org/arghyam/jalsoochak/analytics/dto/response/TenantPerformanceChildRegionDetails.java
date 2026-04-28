package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPerformanceChildRegionDetails {

    private Integer lgdId;
    private Integer departmentId;
    private Integer parentLgdId;
    private Integer parentDepartmentId;
    private Integer lgdLevel;
    private String lgdCode;
    private BigDecimal averagePerformanceScore;
}

