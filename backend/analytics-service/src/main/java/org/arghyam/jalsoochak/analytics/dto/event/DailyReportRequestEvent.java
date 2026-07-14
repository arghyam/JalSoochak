package org.arghyam.jalsoochak.analytics.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound request (produced by tenant-service, consumed by analytics-service on
 * {@code common-topic}) asking analytics to compute the Daily Water Service Situation
 * Report KPIs for one officer.
 *
 * <p>Carries only identity + role + the day to cover — no PII.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportRequestEvent {
    private String eventType;          // DAILY_REPORT_REQUEST
    private Integer tenantId;
    private String tenantSchema;       // tenant_<state> — passed through for message-service PII lookup
    private Long officerUserId;
    private String officerUserType;    // SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
    private String reportDate;         // ISO-8601 date the report covers (D-1)
}
