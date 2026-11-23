package org.arghyam.jalsoochak.message.report.layout;

/**
 * {@link java.time.format.DateTimeFormatter} patterns, always applied with {@code Locale.ENGLISH}.
 * Each may be {@code null}, meaning the built-in pattern.
 *
 * @param date      full dates, e.g. the header's report date
 * @param shortDate the week ranges
 * @param time      the daily cut-off
 */
public record DateFormats(String date, String shortDate, String time) {
}
