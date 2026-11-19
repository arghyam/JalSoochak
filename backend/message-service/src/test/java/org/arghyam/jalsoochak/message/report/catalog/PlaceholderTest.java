package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.report.ReportKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderTest {

    @Test
    void dailyPlaceholders() {
        assertThat(Arrays.stream(Placeholder.values()).filter(p -> p.availableIn(ReportKind.DAILY))
                .map(Placeholder::key))
                .containsExactly("officerName", "generatedDate", "reportDate", "cutoff", "supportPhone");
    }

    @Test
    void weeklyPlaceholders() {
        assertThat(Arrays.stream(Placeholder.values()).filter(p -> p.availableIn(ReportKind.WEEKLY))
                .map(Placeholder::key))
                .containsExactly("officerName", "generatedDate", "weekStart", "weekEnd", "previousWeekStart",
                        "previousWeekEnd", "weekStartDayName", "weekEndDayName", "lowLpcdThreshold",
                        "minSupplyDays", "supportPhone");
    }

    @Test
    void looksUpByTheNameWrittenBetweenTheBraces() {
        assertThat(Placeholder.byKey("weekStartDayName")).contains(Placeholder.WEEK_START_DAY_NAME);
        assertThat(Placeholder.byKey("WEEK_START_DAY_NAME")).isEmpty();
        assertThat(Placeholder.byKey("nope")).isEmpty();
        assertThat(Placeholder.SUPPORT_PHONE.token()).isEqualTo("{supportPhone}");
    }

    @Test
    void findsEveryPlaceholderInOrder() {
        assertThat(Placeholder.keysIn("{weekStartDayName} {weekStart} to {weekEndDayName} {weekEnd}"))
                .containsExactly("weekStartDayName", "weekStart", "weekEndDayName", "weekEnd");
        assertThat(Placeholder.keysIn("No placeholders <=15")).isEmpty();
    }

    @Test
    void wellFormedTextHasNoMalformedBraces() {
        assertThat(Placeholder.hasMalformedBraces("00:00 hrs – {cutoff} hrs")).isFalse();
        assertThat(Placeholder.hasMalformedBraces("{unknownButWellFormed}")).isFalse();
        assertThat(Placeholder.hasMalformedBraces("plain")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{cutoff", "cutoff}", "{{cutoff}}", "{}", "{week start}", "{week_start}", "}{"})
    void flagsABraceOutsideAWellFormedPlaceholder(String text) {
        assertThat(Placeholder.hasMalformedBraces(text)).isTrue();
    }
}
