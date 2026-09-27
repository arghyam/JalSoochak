package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Scheme counts for an LGD or department area, broken down along both real status dimensions.
 *
 * <p>Replaces the {@code active_schemes_count}/{@code inactive_schemes_count} pair, which collapsed
 * {@code operating_status} to a binary and had no way to express Partially Operative. The two lists count
 * the same schemes once per dimension, so each sums to {@link #total}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemeStatusBreakdownResponse {

    private Integer total;
    private List<SchemeStatusCountDTO> workStatusCounts;
    private List<SchemeStatusCountDTO> operatingStatusCounts;
}
