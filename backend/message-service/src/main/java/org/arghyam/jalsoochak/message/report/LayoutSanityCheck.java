package org.arghyam.jalsoochak.message.report;

import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.ReportRole;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.FooterElement;
import org.arghyam.jalsoochak.message.report.layout.HeaderElement;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.arghyam.jalsoochak.message.report.layout.Spacer;
import org.arghyam.jalsoochak.message.report.layout.SummaryRowSpec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks that a parsed layout can be drawn for a (report, role) pair: everything it names exists in
 * that report's catalogue, required text is present, widths and placeholders are well formed.
 *
 * <p>The structural subset of tenant-service's write-time validation. It leaves out the limits
 * (counts, lengths, character set) that keep a tenant's PDF readable but don't stop it being
 * drawn. Reports every problem, each with its path, rather than stopping at the first.</p>
 */
final class LayoutSanityCheck {

    private final ReportKind kind;
    private final ReportRole role;
    private final List<String> problems = new ArrayList<>();

    private LayoutSanityCheck(ReportKind kind, ReportRole role) {
        this.kind = kind;
        this.role = role;
    }

    /** The problems found, each as {@code path: message}; empty when the layout is usable. */
    static List<String> problems(ReportKind kind, ReportRole role, ReportLayout layout) {
        LayoutSanityCheck check = new LayoutSanityCheck(kind, role);
        if (layout == null) {
            check.problem("layout", "is required");
        } else {
            check.layout(layout);
        }
        return List.copyOf(check.problems);
    }

    private void layout(ReportLayout layout) {
        for (int i = 0; i < layout.header().size(); i++) {
            header("header[" + i + "]", layout.header().get(i));
        }

        if (layout.sections().isEmpty()) {
            problem("sections", "at least one section is required");
        }
        int summaries = 0;
        int officerTables = 0;
        Set<SchemeList> lists = EnumSet.noneOf(SchemeList.class);
        for (int i = 0; i < layout.sections().size(); i++) {
            String path = "sections[" + i + "]";
            ReportSection section = layout.sections().get(i);
            switch (section) {
                case ReportSection.SummarySection summary -> {
                    if (++summaries > 1) {
                        problem(path, "only one SUMMARY section is allowed");
                    }
                    summary(path, summary);
                }
                case ReportSection.SchemeListSection list -> schemeList(path, list, lists);
                case ReportSection.OfficerPerformanceSection officers -> {
                    if (++officerTables > 1) {
                        problem(path, "only one OFFICER_PERFORMANCE section is allowed");
                    }
                    officerPerformance(path, officers);
                }
            }
            requiredText(path + ".title", section.title());
            for (int f = 0; f < section.footnotes().size(); f++) {
                requiredText(path + ".footnotes[" + f + "]", section.footnotes().get(f));
            }
        }

        for (int i = 0; i < layout.footer().size(); i++) {
            footer("footer[" + i + "]", layout.footer().get(i));
        }
    }

    private void header(String path, HeaderElement element) {
        switch (element) {
            case HeaderElement.Title title -> requiredText(path + ".text", title.text());
            case HeaderElement.Subtitle subtitle -> requiredText(path + ".text", subtitle.text());
            case HeaderElement.Field field -> {
                requiredText(path + ".label", field.label());
                requiredText(path + ".value", field.value());
            }
            case Spacer spacer -> {
                // Nothing to check.
            }
            case HeaderElement.DashboardLink link -> {
                placeholders(path + ".prefix", link.prefix(), false);
                placeholders(path + ".suffix", link.suffix(), false);
            }
        }
    }

    private void footer(String path, FooterElement element) {
        switch (element) {
            case FooterElement.Note note -> requiredText(path + ".text", note.text());
            case FooterElement.SupportContact contact -> {
                if (isBlank(contact.text())) {
                    problem(path + ".text", "is required");
                } else {
                    placeholders(path + ".text", contact.text(), true);
                    if (!contact.text().contains(Placeholder.SUPPORT_PHONE.token())) {
                        problem(path + ".text", "must contain " + Placeholder.SUPPORT_PHONE.token());
                    }
                }
            }
            case Spacer spacer -> {
                // Nothing to check.
            }
        }
    }

    private void summary(String path, ReportSection.SummarySection summary) {
        List<ColumnSpec<SummaryColumn>> columns = summary.columns();
        columns(path, columns);
        if (!columns.isEmpty() && columns.get(0).field() != SummaryColumn.LABEL) {
            problem(path + ".columns[0].field", "must be LABEL");
        }
        for (int i = 0; i < columns.size(); i++) {
            SummaryColumn field = columns.get(i).field();
            if (field != null && !field.availableIn(kind)) {
                problem(path + ".columns[" + i + "].field", notAvailable(field));
            }
        }

        if (summary.rows().isEmpty()) {
            problem(path + ".rows", "at least one row is required");
        }
        Set<SummaryKpi> kpis = EnumSet.noneOf(SummaryKpi.class);
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < summary.rows().size(); i++) {
            String rowPath = path + ".rows[" + i + "]";
            SummaryRowSpec row = summary.rows().get(i);
            if (row.kpi() == null) {
                problem(rowPath + ".kpi", "is required");
            } else {
                if (!row.kpi().availableIn(kind)) {
                    problem(rowPath + ".kpi", notAvailable(row.kpi()));
                }
                if (!kpis.add(row.kpi())) {
                    problem(rowPath + ".kpi", "'" + row.kpi() + "' is already shown");
                }
                if (row.display() != null && !row.kpi().hasPercent()) {
                    problem(rowPath + ".display", "only applies to a KPI with a percentage");
                }
            }
            requiredText(rowPath + ".label", row.label());
            // Two rows labelled alike would read as one KPI shown twice.
            if (row.label() != null && !labels.add(row.label())) {
                problem(rowPath + ".label", "'" + row.label() + "' is already used in this summary");
            }
        }
    }

    private void schemeList(String path, ReportSection.SchemeListSection section, Set<SchemeList> seen) {
        SchemeList list = section.list();
        if (list == null) {
            problem(path + ".list", "is required");
        } else {
            if (!list.availableIn(kind)) {
                problem(path + ".list", notAvailable(list));
            }
            if (!seen.add(list)) {
                problem(path + ".list", "'" + list + "' is already shown");
            }
        }

        List<ColumnSpec<SchemeColumn>> columns = section.columns();
        columns(path, columns);
        for (int i = 0; i < columns.size(); i++) {
            SchemeColumn field = columns.get(i).field();
            if (field != null && list != null && !field.allowedIn(list)) {
                problem(path + ".columns[" + i + "].field", "'" + field + "' is not available in the "
                        + list + " list");
            }
        }
        placeholders(path + ".emptyText", section.emptyText(), false);
    }

    private void officerPerformance(String path, ReportSection.OfficerPerformanceSection section) {
        if (kind != ReportKind.WEEKLY || role != ReportRole.SUB_DIVISIONAL_OFFICER) {
            problem(path, "OFFICER_PERFORMANCE is only available in WEEKLY reports for SUB_DIVISIONAL_OFFICER");
        }
        List<ColumnSpec<OfficerColumn>> columns = section.columns();
        columns(path, columns);
        placeholders(path + ".emptyText", section.emptyText(), false);
    }

    /** What every table's columns need: at least one, each with a field and a label, no repeats, sane widths. */
    private <F extends Enum<F>> void columns(String path, List<ColumnSpec<F>> columns) {
        if (columns.isEmpty()) {
            problem(path + ".columns", "at least one column is required");
            return;
        }
        Set<F> fields = new HashSet<>();
        int withWidth = 0;
        for (int i = 0; i < columns.size(); i++) {
            String columnPath = path + ".columns[" + i + "]";
            ColumnSpec<F> column = columns.get(i);
            if (column.field() == null) {
                problem(columnPath + ".field", "is required");
            } else if (!fields.add(column.field())) {
                problem(columnPath + ".field", "'" + column.field() + "' is already shown");
            }
            requiredText(columnPath + ".label", column.label());
            Float width = column.width();
            if (width != null) {
                withWidth++;
                if (!(width > 0f && width <= 100f)) {
                    problem(columnPath + ".width", "must be greater than 0 and at most 100");
                }
            }
        }
        if (withWidth != 0 && withWidth != columns.size()) {
            problem(path + ".columns", "width must be set on every column or on none");
        }
    }

    private void requiredText(String path, String text) {
        if (isBlank(text)) {
            problem(path, "is required");
            return;
        }
        placeholders(path, text, false);
    }

    private void placeholders(String path, String text, boolean inSupportContact) {
        if (text == null) {
            return;
        }
        if (Placeholder.hasMalformedBraces(text)) {
            problem(path, "has an unmatched brace or a malformed placeholder");
        }
        for (String key : Placeholder.keysIn(text)) {
            Optional<Placeholder> placeholder = Placeholder.byKey(key);
            if (placeholder.isEmpty()) {
                problem(path, "unknown placeholder {" + key + "}");
            } else if (!placeholder.get().availableIn(kind)) {
                problem(path, "placeholder {" + key + "} is not available in " + kind + " reports");
            } else if (placeholder.get() == Placeholder.SUPPORT_PHONE && !inSupportContact) {
                problem(path, "placeholder {" + key + "} is only allowed in a SUPPORT_CONTACT footer");
            }
        }
    }

    private String notAvailable(Enum<?> value) {
        return "'" + value + "' is not available in " + kind + " reports";
    }

    private void problem(String path, String message) {
        problems.add(path + ": " + message);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
