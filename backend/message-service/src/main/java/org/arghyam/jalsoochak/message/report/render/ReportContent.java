package org.arghyam.jalsoochak.message.report.render;

import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;

import java.util.List;
import java.util.Optional;

/**
 * The values one report draws: what its placeholders, summary cells, scheme lists and officer rows
 * say for this officer and period. The layout decides which of them appear, and where.
 */
public sealed interface ReportContent permits DailyReportContent, WeeklyReportContent {

    /** What {@code {officerName}} prints when the officer's name could not be resolved. */
    String UNNAMED_OFFICER = "Officer";

    ReportKind kind();

    ReportFormats formats();

    /** May be blank, which leaves out a {@code DASHBOARD_LINK} line. */
    String dashboardUrl();

    /** May be blank, which leaves out a {@code SUPPORT_CONTACT} line. */
    String supportPhone();

    /** The placeholder's value; {@code ""} when this report has none. */
    String placeholder(Placeholder placeholder);

    /** {@code text} with its placeholders filled in; {@code ""} for {@code null}. */
    default String resolve(String text) {
        return PlaceholderText.resolve(text, this::placeholder);
    }

    /**
     * One summary cell other than the label.
     *
     * @param kpi     available in this report
     * @param display how a KPI with a percentage is printed; {@code null} for the default
     * @param column  available in this report, not {@link SummaryColumn#LABEL}
     */
    String summaryCell(SummaryKpi kpi, KpiDisplay display, SummaryColumn column);

    /** The rows of a scheme list; empty when this report does not carry that list. */
    Optional<List<ReportSchemeRow>> schemeRows(SchemeList list);

    /** The Section Officer performance rows; empty when this report does not carry them. */
    Optional<List<WeeklyReportOfficerRow>> officerRows();
}
