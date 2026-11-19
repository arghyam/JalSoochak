package org.arghyam.jalsoochak.message.report.layout;

/**
 * A tenant's {@code SITUATION_REPORT_LAYOUT} config value, as stored by tenant-service's
 * {@code SituationReportLayoutConfigDTO}: for each report, which roles receive it and with which
 * layout.
 *
 * <p>An absent {@code daily} or {@code weekly} block leaves that report exactly as it is without
 * the config: recipients from env, built-in layouts. tenant-service validates the document strictly
 * on write; whoever reads it here chooses whether unknown properties fail the parse.</p>
 *
 * @param settings may be {@code null}: env URL and phone, built-in formats
 */
public record SituationReportLayoutConfig(Integer version, ReportSettings settings,
                                          ReportBlock daily, ReportBlock weekly) {
}
