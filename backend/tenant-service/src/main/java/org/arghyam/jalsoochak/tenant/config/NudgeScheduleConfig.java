package org.arghyam.jalsoochak.tenant.config;

public class NudgeScheduleConfig {

    private final int hour;
    private final int minute;

    private NudgeScheduleConfig(Builder builder) {
        this.hour = builder.hour;
        this.minute = builder.minute;
    }

    public int getHour() { return hour; }
    public int getMinute() { return minute; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int hour;
        private int minute;

        public Builder hour(int hour) { this.hour = hour; return this; }
        public Builder minute(int minute) { this.minute = minute; return this; }

        public NudgeScheduleConfig build() { return new NudgeScheduleConfig(this); }
    }
}
