package org.arghyam.jalsoochak.tenant.config;

import java.time.DayOfWeek;

/**
 * Schedule and reporting window for the per-tenant Weekly Water Service Situation Report job, which
 * serves both the Section Officer and the Sub-Divisional Officer.
 *
 * <p>Two independent settings, both in the cron convention 0–7 where both 0 and 7 mean Sunday and 1
 * means Monday:</p>
 * <ul>
 *   <li>{@code dayOfWeek} (with {@code hour}/{@code minute}) — <em>when the job fires</em>, IST.</li>
 *   <li>{@code weekStartDay} — <em>which seven days it reports on</em>. The window is always the last
 *       complete week that began on this day, so a value of 1 gives Monday–Sunday and 4 gives
 *       Thursday–Wednesday.</li>
 * </ul>
 *
 * <p>The two are deliberately not linked: a tenant can report a Thursday–Wednesday week but deliver it
 * on a Friday. Setting them to different days is legal but usually unintended, so
 * {@code TenantSchedulerManager} warns about it — the further the firing day is past the end of the
 * window, the staler the data is on arrival.</p>
 */
public class WeeklyReportScheduleConfig {

    /** Cron day-of-week value meaning Monday — the default reporting window start. */
    private static final int MONDAY = 1;

    private final int dayOfWeek;
    private final int hour;
    private final int minute;
    private final int weekStartDay;

    private WeeklyReportScheduleConfig(Builder builder) {
        this.dayOfWeek = builder.dayOfWeek;
        this.hour = builder.hour;
        this.minute = builder.minute;
        this.weekStartDay = builder.weekStartDay;
    }

    public int getDayOfWeek() { return dayOfWeek; }
    public int getHour() { return hour; }
    public int getMinute() { return minute; }
    public int getWeekStartDay() { return weekStartDay; }

    /** The day the reported week begins on, as a {@link DayOfWeek}. */
    public DayOfWeek getWeekStartDayOfWeek() { return toDayOfWeek(weekStartDay); }

    /**
     * Converts a cron day-of-week (0–7) to a {@link DayOfWeek}.
     *
     * <p>Only 0 needs special handling: cron 1–7 already matches ISO-8601 1–7 (Monday–Sunday), and cron
     * additionally accepts 0 as an alias for Sunday.</p>
     *
     * <p>Callers must range-check first — this throws {@link java.time.DateTimeException} outside 0–7,
     * and the scheduler treats that as fatal for the whole tenant.</p>
     */
    public static DayOfWeek toDayOfWeek(int cronDayOfWeek) {
        return DayOfWeek.of(cronDayOfWeek == 0 ? 7 : cronDayOfWeek);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int dayOfWeek;
        private int hour;
        private int minute;
        // Defaulted rather than left at the primitive 0, which in the cron convention would mean
        // Sunday: a builder chain that forgets this field must produce the documented Monday default,
        // not silently shift the tenant's reporting week.
        private int weekStartDay = MONDAY;

        public Builder dayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; return this; }
        public Builder hour(int hour) { this.hour = hour; return this; }
        public Builder minute(int minute) { this.minute = minute; return this; }
        public Builder weekStartDay(int weekStartDay) { this.weekStartDay = weekStartDay; return this; }

        public WeeklyReportScheduleConfig build() { return new WeeklyReportScheduleConfig(this); }
    }
}
