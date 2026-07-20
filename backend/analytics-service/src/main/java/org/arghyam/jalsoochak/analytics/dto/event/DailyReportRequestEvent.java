package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private String officerUserType;    // SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
    private String reportDate;         // ISO-8601 date the report covers (D-1)
    private String correlationId;      // ties one report run's logs across tenant/analytics/message

    /** SDO reports only: Section Officer user ids under this SDO (share ≥1 scheme with it). Drives the
     *  per-officer Summary breakdown table; null/empty for a SECTION_OFFICER request. */
    private List<Long> subordinateOfficerUserIds;
}
