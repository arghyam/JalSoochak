package org.arghyam.jalsoochak.message.report.render;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.FooterElement;
import org.arghyam.jalsoochak.message.report.layout.HeaderElement;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.arghyam.jalsoochak.message.report.layout.Spacer;
import org.arghyam.jalsoochak.message.report.layout.SummaryRowSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.arghyam.jalsoochak.message.report.render.ReportPdfCanvas.BLANK_LINE;

/**
 * Draws a situation report from a layout: the header lines, the numbered sections, then the footer
 * lines, on a {@link ReportPdfCanvas}.
 *
 * <p>The only drawing code for situation reports. The built-in layouts go through it like a tenant's
 * own, so the default cannot drift from what tenants get.</p>
 *
 * <p>Defensive. A part of the layout this report cannot draw (a list it does not carry, a column
 * unknown to the table, a KPI from the other report) is left out with a WARN rather than failing the
 * report: validation should have caught it, and an officer is better served by a report missing a
 * section than by none. Sections are numbered over the ones actually drawn.</p>
 *
 * <p>Stateless and thread-safe: everything per render lives in the canvas it opens.</p>
 */
@Component
@Slf4j
public class LayoutPdfRenderer {

    static final float TITLE_SIZE = 16f;
    static final float SUBTITLE_SIZE = 10f;
    static final float FIELD_SIZE = 11f;
    static final float LINK_SIZE = 9f;

    /**
     * Draws the report into {@code doc}, whose pages it adds. The canvas is closed before this
     * returns, so the caller can save the document straight away.
     */
    public void render(PDDocument doc, ReportLayout layout, ReportContent content) throws IOException {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < layout.sections().size(); i++) {
            prepare(layout.sections().get(i), i, content).ifPresent(sections::add);
        }

        try (ReportPdfCanvas canvas = new ReportPdfCanvas(doc, negativeLabels(layout, content))) {
            canvas.newPage();
            for (HeaderElement element : layout.header()) {
                drawHeader(canvas, element, content);
            }
            int number = 0;
            for (Section section : sections) {
                number++;
                canvas.sectionTitle(layout.numberSections() ? number + ". " + section.title() : section.title());
                Table table = section.table();
                canvas.drawTable(table.widths(), table.header(), table.rows(), table.style().bodyFontSize(),
                        table.style().headerFontSize(), table.style().cellPad(), table.centred());
                for (String footnote : section.footnotes()) {
                    canvas.footnote(footnote);
                }
            }
            for (FooterElement element : layout.footer()) {
                drawFooter(canvas, element, content);
            }
        }
    }

    // ---- header and footer ---------------------------------------------------------------------

    private static void drawHeader(ReportPdfCanvas canvas, HeaderElement element, ReportContent content)
            throws IOException {
        switch (element) {
            case HeaderElement.Title title ->
                    canvas.centeredLine(canvas.bold(), TITLE_SIZE, content.resolve(title.text()));
            case HeaderElement.Subtitle subtitle ->
                    canvas.centeredLine(canvas.font(), SUBTITLE_SIZE, content.resolve(subtitle.text()));
            case HeaderElement.Field field ->
                    canvas.labelValueLine(FIELD_SIZE, content.resolve(field.label()), content.resolve(field.value()));
            case Spacer spacer -> canvas.moveDown(BLANK_LINE);
            case HeaderElement.DashboardLink link -> {
                String url = content.dashboardUrl();
                if (url != null && !url.isBlank()) {
                    canvas.headerLinkLine(LINK_SIZE, content.resolve(link.prefix()), url,
                            content.resolve(link.suffix()));
                }
            }
        }
    }

    private static void drawFooter(ReportPdfCanvas canvas, FooterElement element, ReportContent content)
            throws IOException {
        switch (element) {
            case FooterElement.Note note -> canvas.footnote(content.resolve(note.text()));
            case FooterElement.SupportContact contact -> {
                String phone = content.supportPhone();
                if (phone != null && !phone.isBlank()) {
                    canvas.footnote(content.resolve(contact.text()));
                }
            }
            case Spacer spacer -> canvas.moveDown(BLANK_LINE);
        }
    }

    // ---- sections ------------------------------------------------------------------------------

    /**
     * The section as it will be drawn, or empty when this report cannot draw it. Builds every cell
     * before anything is drawn, so a section that fails here leaves no half-drawn table behind.
     */
    static Optional<Section> prepare(ReportSection section, int index, ReportContent content) {
        try {
            Optional<Table> table = switch (section) {
                case ReportSection.SummarySection summary -> summary(summary, index, content);
                case ReportSection.SchemeListSection list -> schemeList(list, index, content);
                case ReportSection.OfficerPerformanceSection officers -> officerPerformance(officers, index, content);
            };
            return table.map(t -> new Section(content.resolve(section.title()), t,
                    section.footnotes().stream().map(content::resolve).toList()));
        } catch (RuntimeException e) {
            log.warn("[ReportLayoutPdf] report={} sections[{}] skipped: it could not be drawn ({})",
                    content.kind(), index, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<Table> summary(ReportSection.SummarySection section, int index, ReportContent content) {
        List<ColumnSpec<SummaryColumn>> columns =
                drawableColumns(section.columns(), field -> field.availableIn(content.kind()), index, content);
        List<SummaryRowSpec> rows = section.rows().stream()
                .filter(row -> row.kpi() != null && row.kpi().availableIn(content.kind()))
                .toList();
        if (rows.size() < section.rows().size()) {
            log.warn("[ReportLayoutPdf] report={} sections[{}] left out {} summary row(s) this report has no"
                    + " value for", content.kind(), index, section.rows().size() - rows.size());
        }
        if (columns.isEmpty() || rows.isEmpty()) {
            return skipped(index, content, "it has no column or no row this report can draw");
        }

        List<String[]> cells = new ArrayList<>();
        for (SummaryRowSpec row : rows) {
            String[] cell = new String[columns.size()];
            for (int c = 0; c < cell.length; c++) {
                SummaryColumn field = columns.get(c).field();
                cell[c] = field == SummaryColumn.LABEL
                        ? content.resolve(row.label())
                        : content.summaryCell(row.kpi(), row.display(), field);
            }
            cells.add(cell);
        }
        return Optional.of(new Table(TableStyle.widths(columns, SummaryColumn::defaultWeight),
                header(columns, content), cells, TableStyle.summary(content.kind()),
                TableStyle.summaryCentring(columns)));
    }

    private static Optional<Table> schemeList(ReportSection.SchemeListSection section, int index,
                                              ReportContent content) {
        SchemeList list = section.list();
        Optional<List<ReportSchemeRow>> rows = list != null ? content.schemeRows(list) : Optional.empty();
        if (rows.isEmpty()) {
            return skipped(index, content, "this report does not carry the list " + list);
        }
        List<ColumnSpec<SchemeColumn>> columns =
                drawableColumns(section.columns(), field -> field.allowedIn(list), index, content);
        if (columns.isEmpty()) {
            return skipped(index, content, "it has no column this list can draw");
        }

        List<String[]> cells = new ArrayList<>();
        if (rows.get().isEmpty()) {
            cells.add(emptyRow(columns, SchemeColumn.SCHEME_NAME, content.resolve(section.emptyText())));
        }
        int serialNo = 0;
        for (ReportSchemeRow row : rows.get()) {
            serialNo++;
            String[] cell = new String[columns.size()];
            for (int c = 0; c < cell.length; c++) {
                cell[c] = columns.get(c).field().cell(row, serialNo);
            }
            cells.add(cell);
        }
        return Optional.of(new Table(TableStyle.widths(columns, SchemeColumn::defaultWeight),
                header(columns, content), cells, TableStyle.list(columns.size()), new boolean[columns.size()]));
    }

    private static Optional<Table> officerPerformance(ReportSection.OfficerPerformanceSection section, int index,
                                                      ReportContent content) {
        Optional<List<WeeklyReportOfficerRow>> rows = content.officerRows();
        if (rows.isEmpty()) {
            return skipped(index, content, "this report does not carry officer rows");
        }
        List<ColumnSpec<OfficerColumn>> columns = drawableColumns(section.columns(), field -> true, index, content);
        if (columns.isEmpty()) {
            return skipped(index, content, "it has no column");
        }

        List<String[]> cells = new ArrayList<>();
        if (rows.get().isEmpty()) {
            cells.add(emptyRow(columns, OfficerColumn.OFFICER_NAME, content.resolve(section.emptyText())));
        }
        int serialNo = 0;
        for (WeeklyReportOfficerRow row : rows.get()) {
            serialNo++;
            String[] cell = new String[columns.size()];
            for (int c = 0; c < cell.length; c++) {
                cell[c] = columns.get(c).field().cell(row, serialNo, content.formats()::decimal);
            }
            cells.add(cell);
        }
        return Optional.of(new Table(TableStyle.widths(columns, OfficerColumn::defaultWeight),
                header(columns, content), cells, TableStyle.list(columns.size()),
                TableStyle.officerCentring(columns)));
    }

    /**
     * The single row an empty table gets, so the section reads as "nothing to act on" rather than a
     * bare header that looks like a rendering failure. The text goes in {@code textColumn}, or the
     * first column when the table does not show it.
     */
    static <F extends Enum<F>> String[] emptyRow(List<ColumnSpec<F>> columns, F textColumn, String text) {
        String[] row = new String[columns.size()];
        Arrays.fill(row, "");
        int at = 0;
        for (int c = 0; c < row.length; c++) {
            if (columns.get(c).field() == textColumn) {
                at = c;
                break;
            }
        }
        row[at] = text;
        return row;
    }

    /**
     * The resolved labels of the summary rows whose KPI is negative, i.e. a rise is bad. The canvas
     * finds a trend cell's row by its label, and draws a negative KPI's up arrow red.
     */
    static Set<String> negativeLabels(ReportLayout layout, ReportContent content) {
        Set<String> labels = new HashSet<>();
        for (ReportSection section : layout.sections()) {
            if (section instanceof ReportSection.SummarySection summary) {
                for (SummaryRowSpec row : summary.rows()) {
                    if (row.kpi() != null && row.kpi().negative() && row.kpi().availableIn(content.kind())) {
                        labels.add(content.resolve(row.label()));
                    }
                }
            }
        }
        return Set.copyOf(labels);
    }

    private static <F extends Enum<F>> List<ColumnSpec<F>> drawableColumns(List<ColumnSpec<F>> columns,
                                                                         Predicate<F> drawable, int index,
                                                                         ReportContent content) {
        List<ColumnSpec<F>> kept = columns.stream()
                .filter(column -> column.field() != null && drawable.test(column.field()))
                .toList();
        if (kept.size() < columns.size()) {
            log.warn("[ReportLayoutPdf] report={} sections[{}] left out {} column(s) this table cannot draw",
                    content.kind(), index, columns.size() - kept.size());
        }
        return kept;
    }

    private static <F extends Enum<F>> String[] header(List<ColumnSpec<F>> columns, ReportContent content) {
        return columns.stream().map(column -> content.resolve(column.label())).toArray(String[]::new);
    }

    private static Optional<Table> skipped(int index, ReportContent content, String reason) {
        log.warn("[ReportLayoutPdf] report={} sections[{}] skipped: {}", content.kind(), index, reason);
        return Optional.empty();
    }

    /** A section ready to draw: its resolved title, table and footnotes. */
    record Section(String title, Table table, List<String> footnotes) {
    }

    /**
     * A table ready to draw.
     *
     * @param widths  in points, summing to the content width
     * @param centred per column
     */
    record Table(float[] widths, String[] header, List<String[]> rows, TableStyle style, boolean[] centred) {
    }
}
