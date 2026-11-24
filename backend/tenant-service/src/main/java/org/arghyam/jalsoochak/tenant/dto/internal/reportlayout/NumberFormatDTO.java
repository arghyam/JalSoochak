package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How decimal figures are printed. Counts are always whole numbers. Each absent field means the
 * built-in value.
 *
 * @param decimals          0 or 1; analytics already rounds to one place
 * @param trimTrailingZeros print {@code 55} rather than {@code 55.0}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NumberFormatDTO(Integer decimals, Boolean trimTrailingZeros) {
}
