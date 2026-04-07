package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in the anomaly status reference list (int code + display string).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyStatusOptionDto {

    /** Stored in {@code anomaly_table.status}. */
    private int value;

    /** Display string aligned with {@link AnomalyStatusDto} labels. */
    private String label;
}
