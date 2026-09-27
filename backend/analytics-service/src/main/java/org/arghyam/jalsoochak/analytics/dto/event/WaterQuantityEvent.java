package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WaterQuantityEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private Integer userId;
    /**
     * The correction path's already-derived daily volume, in the meter's native m&sup3; — telemetry
     * subtracts two {@code NUMERIC} readings, so it is decimal at source. {@code BigDecimal} keeps that
     * precision instead of rounding it away at the topic; the litre conversion happens on ingest.
     */
    private BigDecimal waterQuantity;
    private Integer submissionStatus;
    private String outageReason;
    private String nonSubmissionReason;
    private String date;
}
