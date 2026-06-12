package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NationalDashboardLevel2BoundaryResponse {

    private JsonNode nationalBoundary;
    private List<LgdLevel2Boundary> lgdLevel2Boundaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LgdLevel2Boundary {
        private Integer tenantId;
        private Integer lgdId;
        private Integer tenantStatus;
        private String stateCode;
        private String stateTitle;
        private String title;
        private JsonNode boundary;
    }
}

