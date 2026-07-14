package org.arghyam.jalsoochak.tenant.event;

import lombok.Value;

import java.util.List;

/**
 * Raised (after the config write) when a tenant's — or the system default's (tenantId 0) —
 * {@code INCLUDED_WORK_STATUSES} config changes. Bridged to the {@code INCLUDED_WORK_STATUSES_UPDATED}
 * Kafka event by {@link TenantEventListener}. {@code stateCode} is informational; for the national
 * default (tenantId 0) it carries the {@code NATIONAL} sentinel.
 */
@Value
public class IncludedWorkStatusesUpdatedEvent {
    Integer tenantId;
    String stateCode;
    List<Integer> workStatuses;
}
