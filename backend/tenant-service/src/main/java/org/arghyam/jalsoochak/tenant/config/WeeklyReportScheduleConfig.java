package org.arghyam.jalsoochak.tenant.config;

/**
 * Schedule (day-of-week + hour + minute, IST) for the per-tenant Weekly Water Service Situation
 * Report job, which serves both the Section Officer and the Sub-Divisional Officer.
 *
 * <p>{@code dayOfWeek} follows the cron convention 0–7, where both 0 and 7 mean Sunday and 1 means
 * Monday. The report covers the previous full Monday–Sunday week, so scheduling it on a Monday is the
 * intended use; the field is configurable rather than fixed so a tenant can move delivery without a
 * code change, but moving it off Monday does not move the reported week.</p>
 */
public class WeeklyReportScheduleConfig {

    private final int dayOfWeek;
    private final int hour;
    private final int minute;

    private WeeklyReportScheduleConfig(Builder builder) {
        this.dayOfWeek = builder.dayOfWeek;
        this.hour = builder.hour;
        this.minute = builder.minute;
    }

    public int getDayOfWeek() { return dayOfWeek; }
    public int getHour() { return hour; }
    public int getMinute() { return minute; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int dayOfWeek;
        private int hour;
        private int minute;

        public Builder dayOfWeek(int dayOfWeek) { this.dayOfWeek = dayOfWeek; return this; }
        public Builder hour(int hour) { this.hour = hour; return this; }
        public Builder minute(int minute) { this.minute = minute; return this; }

        public WeeklyReportScheduleConfig build() { return new WeeklyReportScheduleConfig(this); }
    }
}
