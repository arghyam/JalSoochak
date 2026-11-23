package org.arghyam.jalsoochak.message.report.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;

import java.util.List;

/** A titled table in the body of the report, with optional footnotes under it. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReportSection.SummarySection.class, name = "SUMMARY"),
        @JsonSubTypes.Type(value = ReportSection.SchemeListSection.class, name = "SCHEME_LIST"),
        @JsonSubTypes.Type(value = ReportSection.OfficerPerformanceSection.class, name = "OFFICER_PERFORMANCE")
})
public sealed interface ReportSection
        permits ReportSection.SummarySection, ReportSection.SchemeListSection,
        ReportSection.OfficerPerformanceSection {

    /** What an empty list's single row says when the layout does not set it. */
    String DEFAULT_EMPTY_TEXT = "None";

    String title();

    /** Unmodifiable, empty when absent. */
    List<String> footnotes();

    /** The KPI table: one row per KPI, the first column its label. */
    record SummarySection(String title, List<ColumnSpec<SummaryColumn>> columns, List<SummaryRowSpec> rows,
                          List<String> footnotes) implements ReportSection {

        public SummarySection {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
            footnotes = footnotes == null ? List.of() : List.copyOf(footnotes);
        }
    }

    /**
     * One of the scheme lists analytics computed.
     *
     * @param emptyText the single row's text when the list is empty; {@value ReportSection#DEFAULT_EMPTY_TEXT}
     *                  when absent
     */
    record SchemeListSection(SchemeList list, String title, List<ColumnSpec<SchemeColumn>> columns,
                             String emptyText, List<String> footnotes) implements ReportSection {

        public SchemeListSection {
            columns = columns == null ? List.of() : List.copyOf(columns);
            emptyText = emptyText == null ? DEFAULT_EMPTY_TEXT : emptyText;
            footnotes = footnotes == null ? List.of() : List.copyOf(footnotes);
        }
    }

    /**
     * The per-Section-Officer table. Weekly SDO reports only: analytics fills the officer rows for
     * an SDO alone.
     *
     * @param emptyText the single row's text when there are no officers; {@value ReportSection#DEFAULT_EMPTY_TEXT}
     *                  when absent
     */
    record OfficerPerformanceSection(String title, List<ColumnSpec<OfficerColumn>> columns,
                                     String emptyText, List<String> footnotes) implements ReportSection {

        public OfficerPerformanceSection {
            columns = columns == null ? List.of() : List.copyOf(columns);
            emptyText = emptyText == null ? DEFAULT_EMPTY_TEXT : emptyText;
            footnotes = footnotes == null ? List.of() : List.copyOf(footnotes);
        }
    }
}
