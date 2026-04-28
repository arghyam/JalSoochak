package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantDetailsResponse {

    private Integer tenantId;
    private String stateCode;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private Integer childBoundaryCount;
    private BigDecimal averageSchemeRegularity;
    private BigDecimal readingSubmissionRate;
    private List<ChildRegionDetails> childRegions;
}
