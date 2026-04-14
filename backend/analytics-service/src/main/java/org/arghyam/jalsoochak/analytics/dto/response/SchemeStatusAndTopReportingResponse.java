package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemeStatusAndTopReportingResponse {

    private Integer parentLgdId;
    private Integer parentDepartmentId;
    private String parentLgdCName;
    private String parentDepartmentCName;
    private String parentLgdTitle;
    private String parentDepartmentTitle;
    private Integer parentLgdLevel;
    private Integer parentDepartmentLevel;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysInRange;
    private Integer activeSchemeCount;
    private Integer inactiveSchemeCount;
    private Long totalCount;
    private Integer topSchemeCount;
    private List<TopReportingScheme> topSchemes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopReportingScheme {
        private Integer schemeId;
        private String schemeName;
        private Integer statusCode;
        private String status;
        private Integer submissionDays;
        private BigDecimal reportingRate;
        private Long totalWaterSupplied;
        private Integer immediateParentLgdId;
        private String immediateParentLgdCName;
        private String immediateParentLgdTitle;
        private Integer immediateParentLgdLevel;
        private Integer immediateParentDepartmentId;
        private String immediateParentDepartmentCName;
        private String immediateParentDepartmentTitle;
        private Integer immediateParentDepartmentLevel;
        private Map<String, Integer> lgdLadder;
        private Map<String, Integer> departmentLadder;
    }
}
