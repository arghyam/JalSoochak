package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@link java.time.format.DateTimeFormatter} patterns, applied with {@code Locale.ENGLISH}. Each
 * absent one means the built-in pattern.
 *
 * @param date      full dates, e.g. the header's report date
 * @param shortDate the week ranges
 * @param time      the daily cut-off
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DateFormatsDTO(String date, String shortDate, String time) {
}
