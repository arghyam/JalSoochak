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
public class CriticalSchemesResponse {
    private Long criticalSchemeCount;
    private Boolean list;
    private Integer page;
    private Integer limit;
    private List<CriticalSchemeListItem> schemes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriticalSchemeListItem {
        private Integer schemeId;
        private String schemeName;
        private LocalDate lastSuppliedDate;
    }
}

