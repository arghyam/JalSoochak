package org.arghyam.jalsoochak.tenant.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Published to {@code common-topic} to ask analytics-service to compute the Daily Water Service
 * Situation Report KPIs for one officer. Carries only identity + role + the day to cover — no PII.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportRequestEvent {
    private String eventType;          // DAILY_REPORT_REQUEST
    private Integer tenantId;
    private String tenantSchema;
    private Long officerUserId;
    private String officerUserType;    // SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
    private String reportDate;         // ISO-8601 date the report covers (D-1)
    private String correlationId;      // ties one report run's logs across tenant/analytics/message
}
