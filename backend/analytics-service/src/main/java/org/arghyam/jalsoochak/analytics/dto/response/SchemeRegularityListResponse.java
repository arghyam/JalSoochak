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
    private List<SchemeStatusCountDTO> workStatusCounts;
    private List<SchemeStatusCountDTO> operatingStatusCounts;
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
        private SchemeStatusDTO workStatus;
        private SchemeStatusDTO operatingStatus;
        private Integer supplyDays;
        private BigDecimal averageRegularity;
        /** Whether this scheme met the regularity threshold over the window (additive; rate unchanged). */
        private Boolean isRegular;
        private Integer submissionDays;
        private BigDecimal submissionRate;
    }
}
