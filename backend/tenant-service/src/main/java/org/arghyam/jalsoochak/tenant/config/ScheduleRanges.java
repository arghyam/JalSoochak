package org.arghyam.jalsoochak.tenant.config;

/**
 * Range checks shared by the schedule-config builders. A builder that throws makes
 * {@code TenantConfigService} fall back to the default schedule.
 */
final class ScheduleRanges {

    private ScheduleRanges() {
    }

    static void checkHour(int hour) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be in [0,23]");
        }
    }

    static void checkMinute(int minute) {
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("minute must be in [0,59]");
        }
    }

    /** Cron convention 0–7, where both 0 and 7 are Sunday. */
    static void checkCronDayOfWeek(String field, int dayOfWeek) {
        if (dayOfWeek < 0 || dayOfWeek > 7) {
            throw new IllegalArgumentException(field + " must be in [0,7]");
        }
    }
}
