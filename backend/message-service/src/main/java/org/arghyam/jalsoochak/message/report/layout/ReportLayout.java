package org.arghyam.jalsoochak.message.report.layout;

import java.util.List;

/**
 * A complete report layout: header lines, the sections in order, and footer lines.
 *
 * @param numberSections whether section titles are drawn as {@code "<n>. <title>"}, numbered over
 *                       the sections actually drawn; defaults to {@code true}
 * @param header         unmodifiable, empty when absent
 * @param sections       unmodifiable, empty when absent
 * @param footer         unmodifiable, empty when absent
 */
public record ReportLayout(Boolean numberSections, List<HeaderElement> header,
                           List<ReportSection> sections, List<FooterElement> footer) {

    public ReportLayout {
        numberSections = numberSections == null || numberSections;
        header = header == null ? List.of() : List.copyOf(header);
        sections = sections == null ? List.of() : List.copyOf(sections);
        footer = footer == null ? List.of() : List.copyOf(footer);
    }
}
