package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Inbound request (produced by tenant-service, consumed by analytics-service on
 * {@code common-topic}) asking analytics to compute the Weekly Water Service Situation Report KPIs
 * for one officer.
 *
 * <p>Carries only identity + role + the weeks to cover — no PII. Both week ranges travel on the
 * event rather than being re-derived here, so every service in the run agrees on which days the
 * report covers even if the job straddles midnight or is replayed later.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WeeklyReportRequestEvent {
    private String eventType;          // WEEKLY_REPORT_REQUEST
    private Integer tenantId;
    private String tenantSchema;       // tenant_<state> — passed through for message-service PII lookup
    private Long officerUserId;
    private String officerUserType;    // SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
    private String weekStart;          // ISO-8601, first day of the reported week
    private String weekEnd;            // ISO-8601, last day of the reported week
    private String previousWeekStart;  // ISO-8601, first day of the comparison week
    private String previousWeekEnd;    // ISO-8601, last day of the comparison week
    private String correlationId;      // ties one report run's logs across tenant/analytics/message

    /** SDO reports only: Section Officer user ids under this SDO (share >= 1 scheme with it). Drives
     *  the per-officer performance table; null/empty for a SECTION_OFFICER request. */
    private List<Long> subordinateOfficerUserIds;
}
