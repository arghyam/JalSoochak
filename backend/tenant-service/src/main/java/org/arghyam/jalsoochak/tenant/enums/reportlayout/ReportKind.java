package org.arghyam.jalsoochak.tenant.enums.reportlayout;

/**
 * The two Water Service Situation Reports a {@code SITUATION_REPORT_LAYOUT} config describes.
 *
 * <p>Part of the layout catalogue, which message-service (the renderer) defines too: a value added
 * here must be added there first, and message-service deployed first.</p>
 */
public enum ReportKind {
    DAILY,
    WEEKLY;

    /**
     * Whether message-service ships a built-in layout for this report and role, so that the role may
     * be given {@code "useDefault": true}. A daily report to a Sub-Divisional Officer has none.
     */
    public boolean hasBuiltInLayout(ReportRole role) {
        return this == WEEKLY || role == ReportRole.SECTION_OFFICER;
    }
}
