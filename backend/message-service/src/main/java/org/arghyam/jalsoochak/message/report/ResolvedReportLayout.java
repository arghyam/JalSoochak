package org.arghyam.jalsoochak.message.report;

import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.render.ReportFormats;

import java.util.Objects;

/**
 * Everything one report is drawn with: the layout, and the settings in effect for the tenant (its
 * own where it set them, the environment's otherwise).
 *
 * @param layout       built-in or the tenant's own
 * @param formats      how dates and numbers are printed
 * @param dashboardUrl blank leaves out a {@code DASHBOARD_LINK} line; never {@code null}
 * @param supportPhone blank leaves out a {@code SUPPORT_CONTACT} line; never {@code null}
 */
public record ResolvedReportLayout(ReportLayout layout, ReportFormats formats,
                                   String dashboardUrl, String supportPhone) {

    public ResolvedReportLayout {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(formats, "formats");
        dashboardUrl = dashboardUrl == null ? "" : dashboardUrl;
        supportPhone = supportPhone == null ? "" : supportPhone;
    }

    /** What has to be looked up to draw {@link #layout}. */
    public ReportDataNeeds needs() {
        return ReportDataNeeds.of(layout);
    }
}
