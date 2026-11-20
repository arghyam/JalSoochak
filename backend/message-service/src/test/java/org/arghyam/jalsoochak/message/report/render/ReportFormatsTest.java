package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi.ValueType;
import org.arghyam.jalsoochak.message.report.layout.DateFormats;
import org.arghyam.jalsoochak.message.report.layout.NumberFormatSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportFormatsTest {

    private static final LocalDate SUNDAY_19_JULY = LocalDate.of(2026, 7, 19);
    private final ReportFormats defaults = ReportFormats.defaults();

    @Nested
    class Dates {

        @Test
        void printsTheBuiltInPatterns() {
            assertThat(defaults.date(SUNDAY_19_JULY)).isEqualTo("19 July 2026");
            assertThat(defaults.shortDate(SUNDAY_19_JULY)).isEqualTo("19-Jul-2026");
            assertThat(defaults.time(LocalTime.of(16, 0))).isEqualTo("16:00");
            assertThat(ReportFormats.dayName(SUNDAY_19_JULY)).isEqualTo("Sunday");
        }

        @Test
        void appliesTheConfiguredPatterns() {
            ReportFormats formats = ReportFormats.of(new DateFormats("d MMM yy", "dd/MM/yyyy", "hh:mm a"), null);

            assertThat(formats.date(SUNDAY_19_JULY)).isEqualTo("19 Jul 26");
            assertThat(formats.shortDate(SUNDAY_19_JULY)).isEqualTo("19/07/2026");
            assertThat(formats.time(LocalTime.of(16, 0))).isEqualTo("04:00 PM");
        }

        @Test
        void fallsBackToTheBuiltInPatternForEachOneLeftOut() {
            ReportFormats formats = ReportFormats.of(new DateFormats(null, "dd/MM/yyyy", null), null);

            assertThat(formats.date(SUNDAY_19_JULY)).isEqualTo("19 July 2026");
            assertThat(formats.shortDate(SUNDAY_19_JULY)).isEqualTo("19/07/2026");
            assertThat(formats.time(LocalTime.of(16, 0))).isEqualTo("16:00");
        }

        @Test
        void formatsInEnglishWhateverTheJvmDefaultLocaleIs() {
            // Month names reach a PDF font that draws what it cannot encode as '?', so a JVM started
            // with e.g. hi-IN would otherwise ship a header of question marks. The formats are built
            // after the default changes, so this proves the pin rather than the class-init locale.
            Locale base = Locale.getDefault();
            Locale display = Locale.getDefault(Locale.Category.DISPLAY);
            Locale format = Locale.getDefault(Locale.Category.FORMAT);
            Locale.setDefault(Locale.forLanguageTag("hi-IN"));
            try {
                ReportFormats formats = ReportFormats.of(null, null);

                assertThat(formats.date(SUNDAY_19_JULY)).isEqualTo("19 July 2026");
                assertThat(formats.shortDate(SUNDAY_19_JULY)).isEqualTo("19-Jul-2026");
                assertThat(ReportFormats.dayName(SUNDAY_19_JULY)).isEqualTo("Sunday");
            } finally {
                Locale.setDefault(base);
                Locale.setDefault(Locale.Category.DISPLAY, display);
                Locale.setDefault(Locale.Category.FORMAT, format);
            }
        }

        @Test
        void rejectsAnInvalidPattern() {
            assertThatThrownBy(() -> ReportFormats.of(new DateFormats("'unclosed", null, null), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Numbers {

        @Test
        void printsCountsWhole() {
            assertThat(defaults.count(14200.0)).isEqualTo("14200");
            assertThat(defaults.count(0.0)).isEqualTo("0");
            assertThat(defaults.value(148.0, ValueType.COUNT)).isEqualTo("148");
        }

        @Test
        void printsDecimalsToOnePlaceWithoutATrailingZero() {
            assertThat(defaults.decimal(63.4)).isEqualTo("63.4");
            assertThat(defaults.decimal(63.0)).isEqualTo("63");
            assertThat(defaults.decimal(100.0)).isEqualTo("100");
            assertThat(defaults.decimal(0.0)).isEqualTo("0");
            assertThat(defaults.value(95.9, ValueType.DECIMAL)).isEqualTo("95.9");
        }

        @Test
        void roundsTheShortestRepresentationHalfUp() {
            // 63.4 - 61.2 in binary floating point.
            assertThat(defaults.decimal(2.1999999999999957)).isEqualTo("2.2");
            assertThat(defaults.decimal(63.45)).isEqualTo("63.5");
        }

        @Test
        void appliesTheConfiguredPrecision() {
            ReportFormats whole = ReportFormats.of(null, new NumberFormatSpec(0, null));
            ReportFormats keepZeros = ReportFormats.of(null, new NumberFormatSpec(null, false));

            assertThat(whole.decimal(63.4)).isEqualTo("63");
            assertThat(whole.decimal(63.5)).isEqualTo("64");
            assertThat(keepZeros.decimal(63.0)).isEqualTo("63.0");
            assertThat(keepZeros.decimal(0.0)).isEqualTo("0.0");
            assertThat(keepZeros.count(148.0)).isEqualTo("148");
        }

        @Test
        void printsANonFiniteValueRatherThanFailing() {
            assertThat(defaults.decimal(Double.NaN)).isEqualTo("NaN");
            assertThat(defaults.count(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
        }

        @Test
        void matchesBothFormattersItReplacedForEveryOneDecimalValue() {
            // Analytics rounds every decimal figure to one place, so on these inputs the daily report's
            // String.valueOf and the weekly report's round-to-1dp printed the same, and so must this.
            for (int tenths = -20_000; tenths <= 20_000; tenths++) {
                double value = tenths / 10.0;
                assertThat(defaults.decimal(value)).as("%s", value)
                        .isEqualTo(legacyDailyFmt(value))
                        .isEqualTo(legacyWeeklyFmt(value));
            }
        }
    }

    @Nested
    class Trend {

        @Test
        void marksARiseAFallAndNoChange() {
            assertThat(defaults.trend(2, ValueType.COUNT)).isEqualTo("▲ +2");
            assertThat(defaults.trend(-2, ValueType.COUNT)).isEqualTo("▼ -2");
            assertThat(defaults.trend(0, ValueType.COUNT)).isEqualTo("—");
            assertThat(defaults.trend(63.4 - 61.2, ValueType.DECIMAL)).isEqualTo("▲ +2.2");
            assertThat(defaults.trend(58.5 - 60.0, ValueType.DECIMAL)).isEqualTo("▼ -1.5");
            assertThat(defaults.trend(2.0, ValueType.DECIMAL)).isEqualTo("▲ +2");
        }

        @Test
        void showsNoChangeForADeltaThatRoundsToZeroAtThePrintedPrecision() {
            assertThat(defaults.trend(-0.04, ValueType.DECIMAL)).isEqualTo("—");
            assertThat(ReportFormats.of(null, new NumberFormatSpec(0, null)).trend(0.4, ValueType.DECIMAL))
                    .isEqualTo("—");
            assertThat(defaults.trend(Double.NaN, ValueType.DECIMAL)).isEqualTo("—");
        }

        @Test
        void matchesTheTrendItReplacedForOneDecimalValues() {
            for (int current = 0; current <= 1500; current += 7) {
                for (int previous = 0; previous <= 1500; previous += 11) {
                    double delta = current / 10.0 - previous / 10.0;
                    assertThat(defaults.trend(delta, ValueType.DECIMAL)).as("%s", delta)
                            .isEqualTo(legacyTrendDouble(delta));
                    assertThat(defaults.trend(current - previous, ValueType.COUNT))
                            .isEqualTo(legacyTrendInt(current - previous));
                }
            }
        }
    }

    // The formatters the hand-drawn reports used, kept here as the reference the new one must match.

    private static String legacyDailyFmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String legacyWeeklyFmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private static String legacyTrendInt(int delta) {
        if (delta == 0) return "—";
        return (delta > 0 ? "▲ +" : "▼ ") + delta;
    }

    private static String legacyTrendDouble(double delta) {
        double rounded = Math.round(delta * 10.0) / 10.0;
        if (rounded == 0.0) return "—";
        return (rounded > 0 ? "▲ +" : "▼ ") + legacyWeeklyFmt(rounded);
    }
}
