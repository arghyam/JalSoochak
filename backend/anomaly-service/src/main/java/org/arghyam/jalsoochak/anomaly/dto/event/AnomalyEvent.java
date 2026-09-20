package org.arghyam.jalsoochak.anomaly.dto.event;

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
    /** The dedup key {@code anomaly_table.uuid} is derived from — not a pointer. Do not repurpose. */
    private String correlationId;
    /**
     * ANOMALY-SUBMISSION-LINK: {@code flow_reading_table.correlation_id} of the submission that
     * caused this anomaly. Null for the types raised without one (1, 3, 4, 6, 9).
     */
    private String submissionCorrelationId;
}
