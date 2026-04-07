package org.arghyam.jalsoochak.tenant.event;

import lombok.Value;

@Value
public class WaterSupplyThresholdUpdatedEvent {
    Integer tenantId;
    String stateCode;
    int underSupplyThresholdPercent;
    int overSupplyThresholdPercent;
}
