package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Consumed from {@code tenant-service-topic}: the set of scheme {@code work_status} codes counted in
 * a tenant's dashboards (or the national default when {@code tenantId} = 0). Persisted to
 * {@code dim_tenant_table.included_work_statuses} and read by {@code DashboardWorkStatusFilter}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IncludedWorkStatusesUpdatedEvent {
    private String eventType;
    private Integer tenantId;
    private String stateCode;
    private List<Integer> workStatuses;
}
