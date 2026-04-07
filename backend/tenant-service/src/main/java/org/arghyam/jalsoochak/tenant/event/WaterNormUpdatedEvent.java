package org.arghyam.jalsoochak.tenant.event;

import lombok.Value;

@Value
public class WaterNormUpdatedEvent {
    Integer tenantId;
    String stateCode;
    int waterNorm;
}
