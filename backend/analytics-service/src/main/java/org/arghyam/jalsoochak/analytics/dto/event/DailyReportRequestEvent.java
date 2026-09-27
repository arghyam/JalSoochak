package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyReportRequestEvent {
    private String eventType;          // DAILY_REPORT_REQUEST
    private Integer tenantId;
    private String tenantSchema;       // tenant_<state> — passed through for message-service PII lookup
    private Long officerUserId;
    private String officerUserType;    // SECTION_OFFICER
    private String reportDate;         // ISO-8601 date the report covers (today, IST)

    /**
     * ISO-8601 local date-time (IST) the data window closes at — the instant the job ran. Anomaly
     * queries bound by it so a replay reproduces the delivered numbers. Null falls back to the end of
     * the report day.
     */
    private String cutoffIst;
    private String correlationId;      // ties one report run's logs across tenant/analytics/message
}
