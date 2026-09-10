package org.arghyam.jalsoochak.scheme.dto;

import lombok.Builder;

import java.util.List;

/**
 * Scheme counts for a tenant, broken down along both real status dimensions.
 *
 * <p>Replaces the {@code activeSchemes}/{@code inactiveSchemes} pair and the synthetic
 * {@code statusCounts:[ACTIVE, INACTIVE]} list, which collapsed {@code operating_status} to a binary
 * and had no way to express Partially Operative. The two lists count the same schemes once per
 * dimension, so each sums to {@link #totalSchemes}.
 *
 * <p>Named for the breakdown rather than the counts so it cannot be misread as the singular
 * {@link SchemeStatusCountDTO}, which is one bucket <em>within</em> these lists.
 */
@Builder
public record SchemeStatusBreakdownDTO(
        long totalSchemes,
        List<SchemeStatusCountDTO> workStatusCounts,
        List<SchemeStatusCountDTO> operatingStatusCounts
) {
}
