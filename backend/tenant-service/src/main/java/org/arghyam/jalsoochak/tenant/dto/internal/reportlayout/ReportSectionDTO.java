package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import java.util.List;

import org.arghyam.jalsoochak.tenant.enums.reportlayout.OfficerColumn;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SchemeColumn;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SchemeList;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.SummaryColumn;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A titled table in the body of the report, with optional footnotes under it. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReportSectionDTO.SummarySection.class, name = "SUMMARY"),
        @JsonSubTypes.Type(value = ReportSectionDTO.SchemeListSection.class, name = "SCHEME_LIST"),
        @JsonSubTypes.Type(value = ReportSectionDTO.OfficerPerformanceSection.class, name = "OFFICER_PERFORMANCE")
})
public sealed interface ReportSectionDTO
        permits ReportSectionDTO.SummarySection, ReportSectionDTO.SchemeListSection,
        ReportSectionDTO.OfficerPerformanceSection {

    String title();

    /** {@code null} when absent. */
    List<String> footnotes();

    /** The KPI table: one row per KPI, the first column its label. At most one per layout. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SummarySection(String title, List<ColumnSpecDTO<SummaryColumn>> columns, List<SummaryRowDTO> rows,
                          List<String> footnotes) implements ReportSectionDTO {
        public SummarySection {
            columns = Copies.list(columns);
            rows = Copies.list(rows);
            footnotes = Copies.list(footnotes);
        }
    }

    /**
     * One of the scheme lists analytics computed.
     *
     * @param emptyText the single row's text when the list is empty; absent means {@code None}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SchemeListSection(SchemeList list, String title, List<ColumnSpecDTO<SchemeColumn>> columns,
                             String emptyText, List<String> footnotes) implements ReportSectionDTO {
        public SchemeListSection {
            columns = Copies.list(columns);
            footnotes = Copies.list(footnotes);
        }
    }

    /**
     * The per-Section-Officer table. Weekly reports to a Sub-Divisional Officer only: analytics fills
     * the officer rows for an SDO alone.
     *
     * @param emptyText the single row's text when there are no officers; absent means {@code None}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OfficerPerformanceSection(String title, List<ColumnSpecDTO<OfficerColumn>> columns,
                                     String emptyText, List<String> footnotes) implements ReportSectionDTO {
        public OfficerPerformanceSection {
            columns = Copies.list(columns);
            footnotes = Copies.list(footnotes);
        }
    }
}
