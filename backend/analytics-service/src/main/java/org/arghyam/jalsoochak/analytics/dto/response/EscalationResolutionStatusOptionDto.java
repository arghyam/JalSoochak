package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the escalation resolution status reference list (int code + display string).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscalationResolutionStatusOptionDto {

    /** Stored in {@code fact_escalation_table.resolution_status}. */
    private int value;

    /** Display string aligned with {@link EscalationResolutionStatusDto} labels. */
    private String label;
}
