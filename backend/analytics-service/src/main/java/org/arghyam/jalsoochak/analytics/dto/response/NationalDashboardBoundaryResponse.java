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
public class NationalDashboardBoundaryResponse {

    private JsonNode nationalBoundary;
    private List<StateBoundary> stateWiseBoundaries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateBoundary {
        private Integer tenantId;
        private Integer lgdId;
        private Integer tenantStatus;
        private String stateCode;
        private String stateTitle;
        private JsonNode boundary;
    }
}
