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
public class WaterQuantityEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private Integer userId;
    /**
     * The correction paths' daily volume in the meter's native m&sup3;, carried at the precision they
     * computed it — the difference of two {@code NUMERIC} readings. Analytics converts to the litres its
     * column stores; this service does not, so that the two never disagree about the unit.
     */
    private BigDecimal waterQuantity;
    private Integer submissionStatus;
    private String outageReason;
    private String nonSubmissionReason;
    private String date;
}
