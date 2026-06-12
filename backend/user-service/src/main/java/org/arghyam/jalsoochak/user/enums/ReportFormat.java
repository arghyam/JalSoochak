package org.arghyam.jalsoochak.user.enums;

import java.util.Locale;

/**
 * Supported export file formats. The {@link #key()} matches the value
 * stored in {@code reports_table.format} and exposed in the API.
 */
public enum ReportFormat {
    CSV("CSV", "csv", "text/csv"),
    XLSX("XLSX", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final String key;
    private final String extension;
    private final String contentType;

    ReportFormat(String key, String extension, String contentType) {
        this.key = key;
        this.extension = extension;
        this.contentType = contentType;
    }

    public String key() {
        return key;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static ReportFormat fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("format is required");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CSV" -> CSV;
            case "XLSX" -> XLSX;
            default -> throw new IllegalArgumentException(
                    "Unsupported report format: " + value + " (allowed: CSV, XLSX)");
        };
    }
}
