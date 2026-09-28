package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
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
    /** The dedup key {@code fact_anomaly_table.uuid} is derived from — not a pointer. Do not repurpose. */
    private String correlationId;
    /**
     * ANOMALY-SUBMISSION-LINK: {@code flow_reading_table.correlation_id} of the submission that
     * caused this anomaly, joined against {@code fact_meter_reading_table.correlation_id}. Null for
     * the types raised without a submission (1, 3, 4, 6, 9), and null on every event published by a
     * telemetry-service still on the old contract — this service can be deployed first.
     */
    private String submissionCorrelationId;
}
