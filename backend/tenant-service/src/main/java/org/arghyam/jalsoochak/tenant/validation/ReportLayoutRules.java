package org.arghyam.jalsoochak.tenant.validation;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ColumnSpecDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.FooterElementDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.HeaderElementDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportLayoutDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.ReportSectionDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.SpacerDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.reportlayout.SummaryRowDTO;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.OfficerColumn;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.Placeholder;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportKind;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportRole;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SchemeColumn;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SchemeList;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SummaryColumn;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SummaryKpi;

/**
 * The rules one (report, role) layout must satisfy, collected as {@code path: message} problems
 * rather than stopping at the first. Used by {@link SituationReportLayoutValidator}.
 *
 * <p>Besides what message-service needs to draw the layout at all, this enforces the limits that keep
 * the PDF readable: element and column counts, text lengths, and a character set the report font
 * (DejaVu Sans) can print without shaping.</p>
 */
final class ReportLayoutRules {

    static final int MAX_HEADER_ELEMENTS = 15;
    static final int MAX_FOOTER_ELEMENTS = 5;
    static final int MAX_SECTIONS = 10;
    static final int MIN_SUMMARY_COLUMNS = 2;
    static final int MAX_SUMMARY_COLUMNS = 4;
    static final int MAX_SUMMARY_ROWS = 12;
    static final int MAX_TABLE_COLUMNS = 10;
    static final int MAX_TEXT_LENGTH = 200;
    static final int MAX_FOOTNOTE_LENGTH = 300;
    static final float MAX_WIDTH = 100f;

    private final ReportKind kind;
    private final ReportRole role;
    private final String basePath;
    private final List<String> problems;

    private ReportLayoutRules(ReportKind kind, ReportRole role, String basePath, List<String> problems) {
        this.kind = kind;
        this.role = role;
        this.basePath = basePath;
        this.problems = problems;
    }

    /**
     * Adds the layout's problems to {@code problems}.
     *
     * @param basePath where the layout sits in the document, e.g. {@code daily.roles.SECTION_OFFICER.layout}
     */
    static void check(ReportKind kind, ReportRole role, String basePath, ReportLayoutDTO layout,
                      List<String> problems) {
        new ReportLayoutRules(kind, role, basePath, problems).layout(layout);
    }

    /**
     * Whether the report font prints {@code codePoint} as is: printable Basic Latin and Latin-1, the
     * general punctuation block's dashes, quotes, bullet and ellipsis, {@code ≤ ≥} and {@code ₹}.
     * Excludes every control character.
     */
    static boolean printable(int codePoint) {
        return (codePoint >= 0x20 && codePoint <= 0x7E)
                || (codePoint >= 0xA0 && codePoint <= 0xFF)
                || (codePoint >= 0x2010 && codePoint <= 0x2027)
                || codePoint == 0x2264 || codePoint == 0x2265
                || codePoint == 0x20B9;
    }

    /** The first code point of {@code text} that {@link #printable} refuses, if any. */
    static Optional<Integer> firstUnprintable(String text) {
        return text.codePoints().filter(c -> !printable(c)).boxed().findFirst();
    }

    /** {@code 'ਰ' (U+0A30)}; only the code for a character that shows as nothing or breaks the line. */
    static String describe(int codePoint) {
        String code = String.format("U+%04X", codePoint);
        boolean invisible = Character.isISOControl(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
        return invisible ? code : "'" + new String(Character.toChars(codePoint)) + "' (" + code + ")";
    }

    private void layout(ReportLayoutDTO layout) {
        List<HeaderElementDTO> header = orEmpty(layout.header());
        if (header.size() > MAX_HEADER_ELEMENTS) {
            problem("header", "at most " + MAX_HEADER_ELEMENTS + " elements are allowed");
        }
        for (int i = 0; i < header.size(); i++) {
            header("header[" + i + "]", header.get(i));
        }

        List<ReportSectionDTO> sections = orEmpty(layout.sections());
        if (sections.isEmpty() || sections.size() > MAX_SECTIONS) {
            problem("sections", "must have 1 to " + MAX_SECTIONS + " sections");
        }
        int summaries = 0;
        int officerTables = 0;
        Set<SchemeList> lists = EnumSet.noneOf(SchemeList.class);
        for (int i = 0; i < sections.size(); i++) {
            String path = "sections[" + i + "]";
            ReportSectionDTO section = sections.get(i);
            if (section == null) {
                problem(path, "must not be null");
                continue;
            }
            switch (section) {
                case ReportSectionDTO.SummarySection summary -> {
                    if (++summaries > 1) {
                        problem(path, "only one SUMMARY section is allowed");
                    }
                    summary(path, summary);
                }
                case ReportSectionDTO.SchemeListSection list -> schemeList(path, list, lists);
                case ReportSectionDTO.OfficerPerformanceSection officers -> {
                    if (++officerTables > 1) {
                        problem(path, "only one OFFICER_PERFORMANCE section is allowed");
                    }
                    officerPerformance(path, officers);
                }
            }
            requiredText(path + ".title", section.title(), MAX_TEXT_LENGTH);
            List<String> footnotes = orEmpty(section.footnotes());
            for (int f = 0; f < footnotes.size(); f++) {
                requiredText(path + ".footnotes[" + f + "]", footnotes.get(f), MAX_FOOTNOTE_LENGTH);
            }
        }

        List<FooterElementDTO> footer = orEmpty(layout.footer());
        if (footer.size() > MAX_FOOTER_ELEMENTS) {
            problem("footer", "at most " + MAX_FOOTER_ELEMENTS + " elements are allowed");
        }
        for (int i = 0; i < footer.size(); i++) {
            footer("footer[" + i + "]", footer.get(i));
        }
    }

    private void header(String path, HeaderElementDTO element) {
        if (element == null) {
            problem(path, "must not be null");
            return;
        }
        switch (element) {
            case HeaderElementDTO.Title title -> requiredText(path + ".text", title.text(), MAX_TEXT_LENGTH);
            case HeaderElementDTO.Subtitle subtitle ->
                    requiredText(path + ".text", subtitle.text(), MAX_TEXT_LENGTH);
            case HeaderElementDTO.Field field -> {
                requiredText(path + ".label", field.label(), MAX_TEXT_LENGTH);
                requiredText(path + ".value", field.value(), MAX_TEXT_LENGTH);
            }
            case SpacerDTO spacer -> {
                // Nothing to check.
            }
            case HeaderElementDTO.DashboardLink link -> {
                optionalText(path + ".prefix", link.prefix(), MAX_TEXT_LENGTH);
                optionalText(path + ".suffix", link.suffix(), MAX_TEXT_LENGTH);
            }
        }
    }

    private void footer(String path, FooterElementDTO element) {
        if (element == null) {
            problem(path, "must not be null");
            return;
        }
        switch (element) {
            case FooterElementDTO.Note note -> requiredText(path + ".text", note.text(), MAX_TEXT_LENGTH);
            case FooterElementDTO.SupportContact contact -> {
                if (isBlank(contact.text())) {
                    problem(path + ".text", "is required");
                    return;
                }
                text(path + ".text", contact.text(), MAX_TEXT_LENGTH, true);
                if (!contact.text().contains(Placeholder.SUPPORT_PHONE.token())) {
                    problem(path + ".text", "must contain " + Placeholder.SUPPORT_PHONE.token());
                }
            }
            case SpacerDTO spacer -> {
                // Nothing to check.
            }
        }
    }

    private void summary(String path, ReportSectionDTO.SummarySection summary) {
        List<ColumnSpecDTO<SummaryColumn>> columns = orEmpty(summary.columns());
        if (columns.size() < MIN_SUMMARY_COLUMNS || columns.size() > MAX_SUMMARY_COLUMNS) {
            problem(path + ".columns", "must have " + MIN_SUMMARY_COLUMNS + " to " + MAX_SUMMARY_COLUMNS
                    + " columns");
        }
        columns(path, columns, field -> field.availableIn(kind) ? null
                : notAvailable(field, SummaryColumn.values(), c -> c.availableIn(kind)));
        if (!columns.isEmpty() && columns.get(0) != null && columns.get(0).field() != null
                && columns.get(0).field() != SummaryColumn.LABEL) {
            problem(path + ".columns[0].field", "must be LABEL");
        }

        List<SummaryRowDTO> rows = orEmpty(summary.rows());
        if (rows.isEmpty() || rows.size() > MAX_SUMMARY_ROWS) {
            problem(path + ".rows", "must have 1 to " + MAX_SUMMARY_ROWS + " rows");
        }
        Set<SummaryKpi> kpis = EnumSet.noneOf(SummaryKpi.class);
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String rowPath = path + ".rows[" + i + "]";
            SummaryRowDTO row = rows.get(i);
            if (row == null) {
                problem(rowPath, "must not be null");
                continue;
            }
            if (row.kpi() == null) {
                problem(rowPath + ".kpi", "is required");
            } else {
                if (!row.kpi().availableIn(kind)) {
                    problem(rowPath + ".kpi",
                            notAvailable(row.kpi(), SummaryKpi.values(), k -> k.availableIn(kind)));
                }
                if (!kpis.add(row.kpi())) {
                    problem(rowPath + ".kpi", "'" + row.kpi() + "' is already shown");
                }
                if (row.display() != null && !row.kpi().hasPercent()) {
                    problem(rowPath + ".display", "only applies to a KPI with a percentage ("
                            + names(SummaryKpi.values(), SummaryKpi::hasPercent) + ")");
                }
            }
            requiredText(rowPath + ".label", row.label(), MAX_TEXT_LENGTH);
            // Two rows labelled alike would read as one KPI shown twice.
            if (row.label() != null && !labels.add(row.label())) {
                problem(rowPath + ".label", "'" + row.label() + "' is already used in this summary");
            }
        }
    }

    private void schemeList(String path, ReportSectionDTO.SchemeListSection section, Set<SchemeList> seen) {
        SchemeList list = section.list();
        if (list == null) {
            problem(path + ".list", "is required");
        } else {
            if (!list.availableIn(kind)) {
                problem(path + ".list", notAvailable(list, SchemeList.values(), l -> l.availableIn(kind)));
            }
            if (!seen.add(list)) {
                problem(path + ".list", "'" + list + "' is already shown");
            }
        }

        List<ColumnSpecDTO<SchemeColumn>> columns = orEmpty(section.columns());
        tableColumnCount(path, columns);
        columns(path, columns, field -> list == null || field.allowedIn(list) ? null
                : "'" + field + "' is only available in the " + SchemeList.ANOMALIES + " list");
        optionalText(path + ".emptyText", section.emptyText(), MAX_TEXT_LENGTH);
    }

    private void officerPerformance(String path, ReportSectionDTO.OfficerPerformanceSection section) {
        if (kind != ReportKind.WEEKLY || role != ReportRole.SUB_DIVISIONAL_OFFICER) {
            problem(path, "OFFICER_PERFORMANCE is only available in WEEKLY reports for SUB_DIVISIONAL_OFFICER");
        }
        List<ColumnSpecDTO<OfficerColumn>> columns = orEmpty(section.columns());
        tableColumnCount(path, columns);
        columns(path, columns, field -> null);
        optionalText(path + ".emptyText", section.emptyText(), MAX_TEXT_LENGTH);
    }

    private void tableColumnCount(String path, List<?> columns) {
        if (columns.isEmpty() || columns.size() > MAX_TABLE_COLUMNS) {
            problem(path + ".columns", "must have 1 to " + MAX_TABLE_COLUMNS + " columns");
        }
    }

    /**
     * What every table's columns need: a field, allowed in this table, shown once; a label; and
     * widths set on every column or none, each in (0, 100].
     *
     * @param fieldProblem why a field can't be used in this table, or {@code null} when it can
     */
    private <F extends Enum<F>> void columns(String path, List<ColumnSpecDTO<F>> columns,
                                             Function<F, String> fieldProblem) {
        Set<F> fields = new HashSet<>();
        int withWidth = 0;
        int present = 0;
        for (int i = 0; i < columns.size(); i++) {
            String columnPath = path + ".columns[" + i + "]";
            ColumnSpecDTO<F> column = columns.get(i);
            if (column == null) {
                problem(columnPath, "must not be null");
                continue;
            }
            present++;
            if (column.field() == null) {
                problem(columnPath + ".field", "is required");
            } else {
                String why = fieldProblem.apply(column.field());
                if (why != null) {
                    problem(columnPath + ".field", why);
                }
                if (!fields.add(column.field())) {
                    problem(columnPath + ".field", "'" + column.field() + "' is already shown");
                }
            }
            requiredText(columnPath + ".label", column.label(), MAX_TEXT_LENGTH);
            Float width = column.width();
            if (width != null) {
                withWidth++;
                if (!(width > 0f && width <= MAX_WIDTH)) {
                    problem(columnPath + ".width", "must be greater than 0 and at most 100");
                }
            }
        }
        if (withWidth != 0 && withWidth != present) {
            problem(path + ".columns", "width must be set on every column or on none");
        }
    }

    private void requiredText(String path, String text, int maxLength) {
        if (isBlank(text)) {
            problem(path, "is required");
            return;
        }
        text(path, text, maxLength, false);
    }

    private void optionalText(String path, String text, int maxLength) {
        if (text != null) {
            text(path, text, maxLength, false);
        }
    }

    private void text(String path, String text, int maxLength, boolean inSupportContact) {
        if (text.length() > maxLength) {
            problem(path, "must be at most " + maxLength + " characters");
        }
        firstUnprintable(text).ifPresent(c -> problem(path, describe(c) + " can't be printed in a report;"
                + " use Latin letters, digits and common punctuation"));
        placeholders(path, text, inSupportContact);
    }

    private void placeholders(String path, String text, boolean inSupportContact) {
        if (Placeholder.hasMalformedBraces(text)) {
            problem(path, "has an unmatched brace or a malformed placeholder");
        }
        for (String key : Placeholder.keysIn(text)) {
            Optional<Placeholder> placeholder = Placeholder.byKey(key);
            if (placeholder.isEmpty() || !placeholder.get().availableIn(kind)) {
                problem(path, "{" + key + "} is not a placeholder of " + kind + " reports (allowed: "
                        + Arrays.stream(Placeholder.values())
                                .filter(p -> p.availableIn(kind))
                                .map(Placeholder::token)
                                .collect(Collectors.joining(", ")) + ")");
            } else if (placeholder.get() == Placeholder.SUPPORT_PHONE && !inSupportContact) {
                problem(path, Placeholder.SUPPORT_PHONE.token() + " is only allowed in a SUPPORT_CONTACT footer");
            }
        }
    }

    private <E extends Enum<E>> String notAvailable(E value, E[] all, Predicate<E> available) {
        return "'" + value + "' is not available in " + kind + " reports (allowed: " + names(all, available) + ")";
    }

    private static <E extends Enum<E>> String names(E[] all, Predicate<E> include) {
        return Arrays.stream(all).filter(include).map(Enum::name).collect(Collectors.joining(", "));
    }

    private void problem(String path, String message) {
        problems.add(basePath + "." + path + ": " + message);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
