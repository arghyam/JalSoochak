package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Consumed from {@code tenant-service-topic}: the percentage of days on which a scheme must supply
 * water to count as regular in a tenant's dashboards (or the national default when {@code tenantId} = 0).
 * Persisted to {@code dim_tenant_table.regularity_threshold_percent} and read by
 * {@code RegularityThresholdFilter}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegularityThresholdUpdatedEvent {
    private String eventType;
    private Integer tenantId;
    private String stateCode;
    private BigDecimal thresholdPercent;
}
