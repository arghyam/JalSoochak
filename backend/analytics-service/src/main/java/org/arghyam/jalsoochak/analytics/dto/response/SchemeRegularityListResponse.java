package org.arghyam.jalsoochak.analytics.dto.response;

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
public class SchemeRegularityListResponse {

    private Integer parentLgdId;
    private Integer parentDepartmentId;
    private String parentLgdCName;
    private String parentDepartmentCName;
    private String parentLgdTitle;
    private String parentDepartmentTitle;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysInRange;
    private Integer totalSchemeCount;
    private Integer activeSchemeCount;
    private Integer inactiveSchemeCount;
    private Integer schemeCountInResponse;
    private List<SchemeMetrics> schemes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemeMetrics {
        private Integer schemeId;
        private String schemeName;
        private Integer stateSchemeId;
        private Integer centreSchemeId;
        private Integer statusCode;
        private String status;
        private Integer supplyDays;
        private BigDecimal averageRegularity;
        private Integer submissionDays;
        private BigDecimal submissionRate;
    }
}
