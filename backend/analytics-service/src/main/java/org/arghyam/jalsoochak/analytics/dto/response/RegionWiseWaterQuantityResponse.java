package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegionWiseWaterQuantityResponse {

    private Integer parentLgdId;
    private Integer parentDepartmentId;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer childRegionCount;
    private List<ChildRegionWaterQuantity> childRegions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildRegionWaterQuantity {
        private Integer lgdId;
        private Integer departmentId;
        private String title;
        private Long waterQuantity;
        private Long householdCount;
        private Long achievedFhtcCount;
        private Long plannedFhtcCount;

        private Long supplyDaysInEfficientRange;
    }
}
