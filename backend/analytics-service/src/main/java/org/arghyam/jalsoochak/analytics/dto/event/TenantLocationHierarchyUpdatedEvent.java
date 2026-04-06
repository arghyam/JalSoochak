package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantLocationHierarchyUpdatedEvent {
    private String eventType;
    private Integer tenantId;
    private String stateCode;
    private String hierarchyType;
    private List<LevelEntry> levels;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LevelEntry {
        private Integer level;
        private String name;
    }
}
