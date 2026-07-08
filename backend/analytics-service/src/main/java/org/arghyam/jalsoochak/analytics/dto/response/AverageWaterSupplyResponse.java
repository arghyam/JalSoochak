package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AverageWaterSupplyResponse {

    private Integer tenantId;
    private String stateCode;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysInRange;
    private Integer schemeCount;
    private Integer childRegionCount;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SchemeWaterSupply> schemes;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ChildRegionWaterSupply> childRegions;
    // The focal region's OWN deduped total (each scheme counted once). Use this for a region's headline
    // figure instead of summing childRegions, which double-counts schemes that span multiple sub-regions.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ChildRegionWaterSupply currentRegion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemeWaterSupply {
        private Integer schemeId;
        private String schemeName;
        private Long householdCount;
        private Long achievedFhtcCount;
        private Long plannedFhtcCount;
        private Long totalWaterSuppliedLiters;
        private Integer supplyDays;
        private BigDecimal avgLitersPerHousehold;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildRegionWaterSupply {
        private Integer lgdId;
        private Integer departmentId;
        private String title;
        private Long totalHouseholdCount;
        private Long totalAchievedFhtcCount;
        private Long totalPlannedFhtcCount;
        private Long totalWaterSuppliedLiters;
        private Integer schemeCount;
        private BigDecimal avgWaterSupplyPerScheme;
    }
}
