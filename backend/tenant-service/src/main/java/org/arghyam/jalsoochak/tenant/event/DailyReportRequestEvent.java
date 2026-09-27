package org.arghyam.jalsoochak.tenant.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private String officerUserType;    // SECTION_OFFICER
    private String reportDate;         // ISO-8601 date the report covers (today, IST)

    /**
     * ISO-8601 local date-time (IST) the data window closes at — the instant the job ran. Anomaly and
     * meter-reading queries bound by it so a replay reproduces the delivered numbers.
     * {@code fact_water_quantity_table} carries no intraday timestamp, so its cut cannot be bounded
     * and stays implicit in when the job ran.
     */
    private String cutoffIst;
    private String correlationId;      // ties one report run's logs across tenant/analytics/message

    /** SDO reports only: Section Officer user ids under this SDO (share ≥1 active scheme with it).
     *  Drives the per-officer Summary breakdown table; null for a SECTION_OFFICER request. */
    private List<Long> subordinateOfficerUserIds;
}
