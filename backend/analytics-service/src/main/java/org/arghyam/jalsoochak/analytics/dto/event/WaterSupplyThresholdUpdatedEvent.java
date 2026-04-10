package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WaterSupplyThresholdUpdatedEvent {
    private String eventType;
    private Integer tenantId;
    private String stateCode;
    private Integer underSupplyThresholdPercent;
    private Integer overSupplyThresholdPercent;
}
