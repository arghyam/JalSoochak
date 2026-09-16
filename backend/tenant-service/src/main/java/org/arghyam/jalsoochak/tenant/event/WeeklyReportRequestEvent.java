package org.arghyam.jalsoochak.tenant.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Published to {@code common-topic} to ask analytics-service to compute the Weekly Water Service
 * Situation Report KPIs for one officer. Carries only identity + role + the weeks to cover — no PII.
 *
 * <p>Both week ranges travel on the event rather than being derived downstream, so every service in
 * the run agrees on which days the report covers even if the job straddles midnight or is replayed
 * days later.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyReportRequestEvent {
    private String eventType;          // WEEKLY_REPORT_REQUEST
    private Integer tenantId;
    private String tenantSchema;
    private Long officerUserId;
    private String officerUserType;    // SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
    private String weekStart;          // ISO-8601, Monday of the reported week
    private String weekEnd;            // ISO-8601, Sunday of the reported week
    private String previousWeekStart;  // ISO-8601, Monday of the comparison week
    private String previousWeekEnd;    // ISO-8601, Sunday of the comparison week
    private String correlationId;      // ties one report run's logs across tenant/analytics/message

    /** SDO reports only: Section Officer user ids under this SDO (share ≥1 active scheme with it).
     *  Drives the per-officer performance table; null for a SECTION_OFFICER request. */
    private List<Long> subordinateOfficerUserIds;
}
