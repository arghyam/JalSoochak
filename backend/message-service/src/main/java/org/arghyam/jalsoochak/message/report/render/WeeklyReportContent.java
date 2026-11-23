package org.arghyam.jalsoochak.message.report.render;

import lombok.Builder;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The Weekly Water Service Situation Report's values for one officer and week, compared with the
 * week before. Immutable.
 */
public final class WeeklyReportContent implements ReportContent {

    private final WeeklyReportKpis.WeekKpis week;
    private final WeeklyReportKpis.WeekKpis previousWeek;
    private final ReportFormats formats;
    private final String dashboardUrl;
    private final String supportPhone;
    private final List<ReportSchemeRow> noSupplyRows;
    private final List<ReportSchemeRow> lowSupplyDaysRows;
    private final List<ReportSchemeRow> lowLpcdRows;
    private final List<WeeklyReportOfficerRow> officerRows;
    private final Map<Placeholder, String> placeholders = new EnumMap<>(Placeholder.class);

    /**
     * @param officerName   {@code null} prints as {@value ReportContent#UNNAMED_OFFICER}
     * @param generatedDate today in IST
     * @param officerRows   the Sub-Divisional Officer's per-Section-Officer rows; {@code null}, like
     *                      every {@code null} list here, is an empty list
     */
    @Builder
    private WeeklyReportContent(WeeklyReportKpis kpis, String officerName, LocalDate generatedDate,
                                ReportFormats formats, String dashboardUrl, String supportPhone,
                                List<ReportSchemeRow> noSupplyRows, List<ReportSchemeRow> lowSupplyDaysRows,
                                List<ReportSchemeRow> lowLpcdRows, List<WeeklyReportOfficerRow> officerRows) {
        Objects.requireNonNull(kpis, "kpis");
        this.week = orEmpty(kpis.getWeek());
        this.previousWeek = orEmpty(kpis.getPreviousWeek());
        this.formats = Objects.requireNonNull(formats, "formats");
        this.dashboardUrl = dashboardUrl;
        this.supportPhone = supportPhone;
        this.noSupplyRows = copyOf(noSupplyRows);
        this.lowSupplyDaysRows = copyOf(lowSupplyDaysRows);
        this.lowLpcdRows = copyOf(lowLpcdRows);
        this.officerRows = copyOf(officerRows);

        placeholders.put(Placeholder.OFFICER_NAME, officerName != null ? officerName : UNNAMED_OFFICER);
        placeholders.put(Placeholder.GENERATED_DATE,
                formats.date(Objects.requireNonNull(generatedDate, "generatedDate")));
        placeholders.put(Placeholder.WEEK_START, date(kpis.getWeekStart(), formats::shortDate));
        placeholders.put(Placeholder.WEEK_END, date(kpis.getWeekEnd(), formats::shortDate));
        placeholders.put(Placeholder.PREVIOUS_WEEK_START, date(kpis.getPreviousWeekStart(), formats::shortDate));
        placeholders.put(Placeholder.PREVIOUS_WEEK_END, date(kpis.getPreviousWeekEnd(), formats::shortDate));
        // Derived, not fixed: the reported week starts on the tenant's configured weekStartDay, so it
        // is only Monday to Sunday by default.
        placeholders.put(Placeholder.WEEK_START_DAY_NAME, date(kpis.getWeekStart(), ReportFormats::dayName));
        placeholders.put(Placeholder.WEEK_END_DAY_NAME, date(kpis.getWeekEnd(), ReportFormats::dayName));
        placeholders.put(Placeholder.SUPPORT_PHONE, supportPhone != null ? supportPhone : "");
    }

    @Override
    public ReportKind kind() {
        return ReportKind.WEEKLY;
    }

    @Override
    public ReportFormats formats() {
        return formats;
    }

    @Override
    public String dashboardUrl() {
        return dashboardUrl;
    }

    @Override
    public String supportPhone() {
        return supportPhone;
    }

    @Override
    public String placeholder(Placeholder placeholder) {
        return placeholders.getOrDefault(placeholder, "");
    }

    /** The reported week, the week before, or the change between them. */
    @Override
    public String summaryCell(SummaryKpi kpi, KpiDisplay display, SummaryColumn column) {
        double current = kpi.weeklyValue(week);
        double previous = kpi.weeklyValue(previousWeek);
        return switch (column) {
            case CURRENT -> formats.value(current, kpi.valueType());
            case PREVIOUS -> formats.value(previous, kpi.valueType());
            case TREND -> formats.trend(current - previous, kpi.valueType());
            case LABEL, VALUE -> "";
        };
    }

    @Override
    public Optional<List<ReportSchemeRow>> schemeRows(SchemeList list) {
        return switch (list) {
            case NO_SUPPLY -> Optional.of(noSupplyRows);
            case LOW_SUPPLY_DAYS -> Optional.of(lowSupplyDaysRows);
            case LOW_LPCD -> Optional.of(lowLpcdRows);
            case ANOMALIES -> Optional.empty();
        };
    }

    @Override
    public Optional<List<WeeklyReportOfficerRow>> officerRows() {
        return Optional.of(officerRows);
    }

    /** An ISO date from the payload, formatted; {@code ""} when the payload has none. */
    private static String date(String iso, Function<LocalDate, String> format) {
        return iso == null || iso.isBlank() ? "" : format.apply(LocalDate.parse(iso));
    }

    private static WeeklyReportKpis.WeekKpis orEmpty(WeeklyReportKpis.WeekKpis kpis) {
        return kpis != null ? kpis : WeeklyReportKpis.WeekKpis.builder().build();
    }

    private static <T> List<T> copyOf(List<T> rows) {
        return rows != null ? List.copyOf(rows) : List.of();
    }
}
