package org.arghyam.jalsoochak.telemetry.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyEvent {

    private String eventType;
    private String uuid;
    private Integer tenantId;
    private Integer type;
    private Integer userId;
    private Integer schemeId;
    private BigDecimal aiReading;
    private BigDecimal aiConfidencePercentage;
    private BigDecimal overriddenReading;
    private Integer retries;
    private BigDecimal previousReading;
    private LocalDate previousReadingDate;
    private Integer consecutiveDaysMissed;
    private String reason;
    private Integer status;
    /**
     * The dedup key, NOT a pointer to anything. Built as
     * {@code UUID(type:userId:schemeId:readingUrl)} for image anomalies and
     * {@code UUID(type:userId:schemeId:readingDate)} for supply anomalies, and
     * {@code anomaly_table.uuid} is derived from it — the UNIQUE constraint on that uuid is what
     * makes a repeat touch an existing row instead of inserting a new one. Do not repurpose it.
     */
    private String correlationId;
    /**
     * ANOMALY-SUBMISSION-LINK: {@code flow_reading_table.correlation_id} of the submission that
     * caused this anomaly, or {@code null} for the types raised without one (1, 3, 4, 6, 9).
     *
     * <p>Separate from {@link #correlationId} because the two answer different questions, and
     * because the tenant-side FK — {@code anomaly_table.flow_reading_id} — is a schema-local
     * surrogate id that means nothing in {@code analytics_schema}. This value is what the warehouse
     * joins on, against {@code fact_meter_reading_table.correlation_id}. That join is a
     * drill-down/trace rather than a counting key: the source column has no unique constraint and
     * the Glific flows deliberately share one value across rows.
     */
    private String submissionCorrelationId;
}
