package org.arghyam.jalsoochak.tenant.event;

import lombok.Value;

/**
 * Raised (after the config write) when a tenant's — or the system default's (tenantId 0) —
 * {@code REGULARITY_THRESHOLD_PERCENT} config changes. Bridged to the
 * {@code REGULARITY_THRESHOLD_UPDATED} Kafka event by {@link TenantEventListener}. {@code stateCode} is
 * informational; for the national default (tenantId 0) it carries the {@code NATIONAL} sentinel.
 */
@Value
public class RegularityThresholdUpdatedEvent {
    Integer tenantId;
    String stateCode;
    Double thresholdPercent;
}
