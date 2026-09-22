package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MeterReadingEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private Integer userId;
    /**
     * Readings are {@code BigDecimal} because the meters carry a decimal digit and
     * {@code flow_reading_table} records it. An event published by a telemetry-service still on the
     * old contract carries a whole number, which deserialises into this field unchanged — so this
     * service can be deployed first, and must be, since it owns the column widening.
     */
    private BigDecimal extractedReading;
    private BigDecimal confirmedReading;
    private Integer confidence;
    private String imageUrl;
    private String readingAt;
    private Integer channel;
    private String readingDate;
    private Integer submissionStatus;
    private Integer readingType;
    /**
     * ANOMALY-SUBMISSION-LINK: {@code flow_reading_table.correlation_id} of the row this event came
     * from, so an anomaly naming the same submission can be joined back to this fact row. Null on
     * events from a telemetry-service still on the old contract.
     */
    private String correlationId;
}
