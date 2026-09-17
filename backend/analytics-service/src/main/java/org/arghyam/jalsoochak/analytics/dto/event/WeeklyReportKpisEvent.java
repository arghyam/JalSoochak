package org.arghyam.jalsoochak.analytics.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.WeeklyReportKpiDTO;

/**
 * Outbound event (produced by analytics-service, consumed by message-service on
 * {@code common-topic}) carrying the computed KPIs for one officer's Weekly Water Service Situation
 * Report.
 *
 * <p>Still carries no PII — message-service resolves the officer's name/phone/WhatsApp contact, and
 * the scheme, village and Jal Mitra details behind the scheme ids, from the operational schema using
 * {@link #officerUserId} and {@link #tenantSchema}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyReportKpisEvent {
    private String eventType;          // WEEKLY_REPORT_KPIS
    private Integer tenantId;
    private String tenantSchema;
    private Long officerUserId;
    private String officerUserType;    // SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
    private String correlationId;      // ties one report run's logs across tenant/analytics/message
    private WeeklyReportKpiDTO kpis;
}
