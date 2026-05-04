package org.arghyam.jalsoochak.analytics.enums;

public enum PeriodScale {
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR;

    public static PeriodScale fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scale is required and must be one of: day, week, month, quarter, year");
        }
        return switch (value.trim().toLowerCase()) {
            case "day", "days" -> DAY;
            case "week", "weeks" -> WEEK;
            case "month", "months" -> MONTH;
            case "quarter", "quarters", "qtr", "qtrs" -> QUARTER;
            case "year", "years", "yr", "yrs" -> YEAR;
            default -> throw new IllegalArgumentException(
                    "Unsupported scale: " + value + ". Use day, week, month, quarter, or year");
        };
    }
}
