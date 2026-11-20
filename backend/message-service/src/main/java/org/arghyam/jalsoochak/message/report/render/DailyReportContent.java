package org.arghyam.jalsoochak.message.report.render;

import lombok.Builder;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The Daily Water Service Situation Report's values for one officer and day. Immutable. */
public final class DailyReportContent implements ReportContent {

    /**
     * The cut-off printed when the event carries none, or one that does not parse: the time the
     * report was first designed around.
     */
    static final LocalTime DEFAULT_CUTOFF = LocalTime.of(16, 0);

    private final DailyReportKpis kpis;
    private final ReportFormats formats;
    private final String dashboardUrl;
    private final String supportPhone;
    private final List<ReportSchemeRow> noSupplyRows;
    private final List<ReportSchemeRow> anomalyRows;
    private final Map<Placeholder, String> placeholders = new EnumMap<>(Placeholder.class);

    /**
     * @param officerName   {@code null} prints as {@value ReportContent#UNNAMED_OFFICER}
     * @param generatedDate today in IST
     * @param noSupplyRows  {@code null} is an empty list
     * @param anomalyRows   {@code null} is an empty list
     */
    @Builder
    private DailyReportContent(DailyReportKpis kpis, String officerName, LocalDate generatedDate,
                               ReportFormats formats, String dashboardUrl, String supportPhone,
                               List<ReportSchemeRow> noSupplyRows, List<ReportSchemeRow> anomalyRows) {
        this.kpis = Objects.requireNonNull(kpis, "kpis");
        this.formats = Objects.requireNonNull(formats, "formats");
        this.dashboardUrl = dashboardUrl;
        this.supportPhone = supportPhone;
        this.noSupplyRows = noSupplyRows != null ? List.copyOf(noSupplyRows) : List.of();
        this.anomalyRows = anomalyRows != null ? List.copyOf(anomalyRows) : List.of();

        placeholders.put(Placeholder.OFFICER_NAME, officerName != null ? officerName : UNNAMED_OFFICER);
        placeholders.put(Placeholder.GENERATED_DATE,
                formats.date(Objects.requireNonNull(generatedDate, "generatedDate")));
        placeholders.put(Placeholder.REPORT_DATE, formats.date(LocalDate.parse(kpis.getReportDate())));
        placeholders.put(Placeholder.CUTOFF, formats.time(cutoff(kpis.getCutoffIst())));
        placeholders.put(Placeholder.SUPPORT_PHONE, supportPhone != null ? supportPhone : "");
    }

    @Override
    public ReportKind kind() {
        return ReportKind.DAILY;
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

    /** The day's single value; the daily summary has no comparison columns. */
    @Override
    public String summaryCell(SummaryKpi kpi, KpiDisplay display, SummaryColumn column) {
        if (column != SummaryColumn.VALUE) {
            return "";
        }
        String value = formats.value(kpi.dailyValue(kpis), kpi.valueType());
        if (!kpi.hasPercent()) {
            return value;
        }
        String percent = formats.decimal(kpi.dailyPercent(kpis)) + "%";
        return switch (display != null ? display : KpiDisplay.COUNT_WITH_PERCENT) {
            case COUNT_WITH_PERCENT -> value + " (" + percent + ")";
            case COUNT -> value;
            case PERCENT -> percent;
        };
    }

    @Override
    public Optional<List<ReportSchemeRow>> schemeRows(SchemeList list) {
        return switch (list) {
            case NO_SUPPLY -> Optional.of(noSupplyRows);
            case ANOMALIES -> Optional.of(anomalyRows);
            case LOW_SUPPLY_DAYS, LOW_LPCD -> Optional.empty();
        };
    }

    @Override
    public Optional<List<WeeklyReportOfficerRow>> officerRows() {
        return Optional.empty();
    }

    /**
     * The time the reporting window closed. Whatever the tenant's schedule actually closed at, not a
     * fixed 16:00: a tenant that moves its schedule would otherwise be told the report covers hours
     * it does not.
     */
    private static LocalTime cutoff(String cutoffIst) {
        if (cutoffIst == null || cutoffIst.isBlank()) {
            return DEFAULT_CUTOFF;
        }
        try {
            return LocalDateTime.parse(cutoffIst).toLocalTime();
        } catch (DateTimeParseException e) {
            return DEFAULT_CUTOFF;
        }
    }
}
