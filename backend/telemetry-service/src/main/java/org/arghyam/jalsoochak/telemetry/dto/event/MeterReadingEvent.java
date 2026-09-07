package org.arghyam.jalsoochak.telemetry.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeterReadingEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private Integer userId;
    /**
     * The readings exactly as {@code flow_reading_table} holds them — {@code NUMERIC}, decimal digit
     * included. They used to be rounded to whole cubic metres on the way out, which cost the warehouse
     * up to 1000 L on every daily volume it derived from them.
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
}
