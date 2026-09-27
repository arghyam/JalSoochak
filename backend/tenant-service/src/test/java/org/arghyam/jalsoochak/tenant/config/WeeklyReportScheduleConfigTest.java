package org.arghyam.jalsoochak.tenant.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DateTimeException;
import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the cron-to-ISO day conversion and the builder defaults that decide a tenant's
 * reporting window when the config omits it.
 */
class WeeklyReportScheduleConfigTest {

    @ParameterizedTest(name = "cron {0} -> {1}")
    @CsvSource({
            "0, SUNDAY",
            "1, MONDAY",
            "2, TUESDAY",
            "3, WEDNESDAY",
            "4, THURSDAY",
            "5, FRIDAY",
            "6, SATURDAY",
            "7, SUNDAY"
    })
    @DisplayName("maps the cron 0-7 convention onto java.time")
    void mapsCronDayOfWeek(int cronDay, DayOfWeek expected) {
        assertThat(WeeklyReportScheduleConfig.toDayOfWeek(cronDay)).isEqualTo(expected);
    }

    @Test
    @DisplayName("0 and 7 are the same day, so a mismatch check must compare converted values")
    void zeroAndSevenAreTheSameDay() {
        assertThat(WeeklyReportScheduleConfig.toDayOfWeek(0))
                .isEqualTo(WeeklyReportScheduleConfig.toDayOfWeek(7));
    }

    @Test
    @DisplayName("rejects out-of-range values rather than silently wrapping")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> WeeklyReportScheduleConfig.toDayOfWeek(8))
                .isInstanceOf(DateTimeException.class);
        assertThatThrownBy(() -> WeeklyReportScheduleConfig.toDayOfWeek(-1))
                .isInstanceOf(DateTimeException.class);
    }

    @Test
    @DisplayName("a builder chain that omits weekStartDay reports a Monday week, not a Sunday one")
    void builderDefaultsWeekStartDayToMonday() {
        // The fields are primitive ints, so without an explicit initialiser this would be 0 — which in
        // the cron convention is Sunday, silently shifting every tenant built this way.
        WeeklyReportScheduleConfig cfg = WeeklyReportScheduleConfig.builder()
                .dayOfWeek(1).hour(9).minute(0)
                .build();

        assertThat(cfg.getWeekStartDay()).isEqualTo(1);
        assertThat(cfg.getWeekStartDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("an explicit Sunday start survives and is distinguishable from the default")
    void explicitSundayIsPreserved() {
        assertThat(WeeklyReportScheduleConfig.builder()
                .dayOfWeek(1).hour(9).minute(0).weekStartDay(0).build()
                .getWeekStartDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    }
}
