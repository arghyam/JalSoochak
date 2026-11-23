package org.arghyam.jalsoochak.message.report.layout;

/**
 * Tenant-wide report settings. Each field may be {@code null}, meaning the env value (URL, phone)
 * or the built-in format.
 */
public record ReportSettings(String dashboardUrl, String supportPhone,
                             DateFormats dateFormats, NumberFormatSpec numberFormat) {
}
