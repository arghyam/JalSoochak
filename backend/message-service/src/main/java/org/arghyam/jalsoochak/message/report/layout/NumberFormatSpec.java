package org.arghyam.jalsoochak.message.report.layout;

/**
 * How decimal figures are printed. Counts are always whole numbers. Each field may be {@code null},
 * meaning the built-in value.
 *
 * @param decimals          0 or 1; analytics already rounds to one place
 * @param trimTrailingZeros print {@code 55} rather than {@code 55.0}
 */
public record NumberFormatSpec(Integer decimals, Boolean trimTrailingZeros) {
}
