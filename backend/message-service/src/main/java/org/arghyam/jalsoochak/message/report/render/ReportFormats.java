package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi.ValueType;
import org.arghyam.jalsoochak.message.report.layout.DateFormats;
import org.arghyam.jalsoochak.message.report.layout.NumberFormatSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.DOWN;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.NO_CHANGE;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.UP;

/**
 * How a report prints dates and numbers. Immutable.
 *
 * <p>Every date pattern is applied with {@link Locale#ENGLISH}: the reports are English throughout,
 * and the PDF font draws a character it cannot encode as {@code ?}, so a JVM started with a
 * non-Latin default locale would otherwise ship month names of question marks.</p>
 *
 * <p>Decimals are rounded half-up on the value's shortest decimal representation, so {@code 2.2}
 * computed as {@code 2.1999999999999957} still prints as {@code 2.2}. Counts are always whole.</p>
 */
public final class ReportFormats {

    public static final String DEFAULT_DATE_PATTERN = "dd MMMM yyyy";
    public static final String DEFAULT_SHORT_DATE_PATTERN = "dd-MMM-yyyy";
    public static final String DEFAULT_TIME_PATTERN = "HH:mm";
    public static final int DEFAULT_DECIMALS = 1;
    public static final boolean DEFAULT_TRIM_TRAILING_ZEROS = true;

    private static final ReportFormats DEFAULTS = of(null, null);

    private final DateTimeFormatter date;
    private final DateTimeFormatter shortDate;
    private final DateTimeFormatter time;
    private final int decimals;
    private final boolean trimTrailingZeros;

    private ReportFormats(DateTimeFormatter date, DateTimeFormatter shortDate, DateTimeFormatter time,
                          int decimals, boolean trimTrailingZeros) {
        this.date = date;
        this.shortDate = shortDate;
        this.time = time;
        this.decimals = decimals;
        this.trimTrailingZeros = trimTrailingZeros;
    }

    /** The built-in formats: {@code 19 July 2026}, {@code 19-Jul-2026}, {@code 16:00}, one decimal place. */
    public static ReportFormats defaults() {
        return DEFAULTS;
    }

    /**
     * The given formats, each absent part taking its built-in value.
     *
     * @throws IllegalArgumentException if a date pattern is invalid
     */
    public static ReportFormats of(DateFormats dateFormats, NumberFormatSpec numberFormat) {
        DateFormats dates = dateFormats != null ? dateFormats : new DateFormats(null, null, null);
        NumberFormatSpec numbers = numberFormat != null ? numberFormat : new NumberFormatSpec(null, null);
        return new ReportFormats(
                formatter(dates.date(), DEFAULT_DATE_PATTERN),
                formatter(dates.shortDate(), DEFAULT_SHORT_DATE_PATTERN),
                formatter(dates.time(), DEFAULT_TIME_PATTERN),
                numbers.decimals() != null ? numbers.decimals() : DEFAULT_DECIMALS,
                numbers.trimTrailingZeros() != null ? numbers.trimTrailingZeros() : DEFAULT_TRIM_TRAILING_ZEROS);
    }

    /** A full date, e.g. the header's {@code 19 July 2026}. */
    public String date(LocalDate value) {
        return value.format(date);
    }

    /** A week-range date, e.g. {@code 13-Jul-2026}. */
    public String shortDate(LocalDate value) {
        return value.format(shortDate);
    }

    /** A time of day, e.g. the daily cut-off {@code 16:00}. */
    public String time(LocalTime value) {
        return value.format(time);
    }

    /** The full English weekday name, e.g. {@code Thursday}. */
    public static String dayName(LocalDate value) {
        return value.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** A whole count, e.g. {@code 14200}. */
    public String count(double value) {
        return Double.isFinite(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }

    /** A decimal figure in the configured number of places, e.g. {@code 63.4}, or {@code 63} for 63.0. */
    public String decimal(double value) {
        return Double.isFinite(value) ? plain(round(value)) : String.valueOf(value);
    }

    /** {@link #count} or {@link #decimal}, by the value's type. */
    public String value(double value, ValueType type) {
        return type == ValueType.COUNT ? count(value) : decimal(value);
    }

    /**
     * A week-on-week change: {@code ▲ +2}, {@code ▼ -1.5}, or {@code —} when it rounds to zero at the
     * printed precision.
     */
    public String trend(double delta, ValueType type) {
        if (!Double.isFinite(delta)) {
            return NO_CHANGE;
        }
        BigDecimal rounded = type == ValueType.COUNT ? BigDecimal.valueOf(Math.round(delta)) : round(delta);
        if (rounded.signum() == 0) {
            return NO_CHANGE;
        }
        String text = type == ValueType.COUNT ? rounded.toPlainString() : plain(rounded);
        return rounded.signum() > 0 ? UP + "+" + text : DOWN + text;
    }

    private BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return (trimTrailingZeros ? value.stripTrailingZeros() : value).toPlainString();
    }

    private static DateTimeFormatter formatter(String pattern, String fallback) {
        return DateTimeFormatter.ofPattern(pattern != null ? pattern : fallback, Locale.ENGLISH);
    }
}
