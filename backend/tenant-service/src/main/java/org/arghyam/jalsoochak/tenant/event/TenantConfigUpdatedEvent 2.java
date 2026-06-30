package org.arghyam.jalsoochak.tenant.event;

import lombok.Value;

import java.util.Set;

@Value
public class TenantConfigUpdatedEvent {
    Integer tenantId;
    String stateCode;
    Set<String> configKeys;
}
