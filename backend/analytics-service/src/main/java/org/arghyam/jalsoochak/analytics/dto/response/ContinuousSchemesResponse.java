package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContinuousSchemesResponse {
    private Long continuousSchemeCount;
    private Boolean list;
    private Integer page;
    private Integer limit;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer daysInRange;
    private List<ContinuousSchemeListItem> schemes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContinuousSchemeListItem {
        private Integer schemeId;
        private String schemeName;
    }
}

