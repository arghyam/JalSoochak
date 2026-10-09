package org.arghyam.jalsoochak.tenant.config;

public class EscalationScheduleConfig {

    private final int hour;
    private final int minute;
    private final int level1Days;
    private final String level1OfficerType;
    private final int level2Days;
    private final String level2OfficerType;

    private EscalationScheduleConfig(Builder builder) {
        this.hour = builder.hour;
        this.minute = builder.minute;
        this.level1Days = builder.level1Days;
        this.level1OfficerType = builder.level1OfficerType;
        this.level2Days = builder.level2Days;
        this.level2OfficerType = builder.level2OfficerType;
    }

    public int getHour() { return hour; }
    public int getMinute() { return minute; }
    public int getLevel1Days() { return level1Days; }
    public String getLevel1OfficerType() { return level1OfficerType; }
    public int getLevel2Days() { return level2Days; }
    public String getLevel2OfficerType() { return level2OfficerType; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int hour;
        private int minute;
        private int level1Days;
        private String level1OfficerType;
        private int level2Days;
        private String level2OfficerType;

        public Builder hour(int hour) { this.hour = hour; return this; }
        public Builder minute(int minute) { this.minute = minute; return this; }
        public Builder level1Days(int level1Days) { this.level1Days = level1Days; return this; }
        public Builder level1OfficerType(String level1OfficerType) {
            this.level1OfficerType = level1OfficerType == null ? null : level1OfficerType.trim();
            return this;
        }
        public Builder level2Days(int level2Days) { this.level2Days = level2Days; return this; }
        public Builder level2OfficerType(String level2OfficerType) {
            this.level2OfficerType = level2OfficerType == null ? null : level2OfficerType.trim();
            return this;
        }

        public EscalationScheduleConfig build() {
            ScheduleRanges.checkHour(hour);
            ScheduleRanges.checkMinute(minute);
            if (level1Days < 0 || level2Days < level1Days) {
                    throw new IllegalArgumentException("Invalid escalation thresholds");
                }
            if (level1OfficerType == null || level1OfficerType.isBlank()
                            || level2OfficerType == null || level2OfficerType.isBlank()) {
                    throw new IllegalArgumentException("Officer types must be configured");
                }
            return new EscalationScheduleConfig(this);
        }
    }
}
