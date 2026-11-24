package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A complete report layout: header lines, the sections in order, and footer lines.
 *
 * @param numberSections whether section titles are drawn as {@code "<n>. <title>"}; absent means yes
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportLayoutDTO(Boolean numberSections, List<HeaderElementDTO> header,
                              List<ReportSectionDTO> sections, List<FooterElementDTO> footer) {
    public ReportLayoutDTO {
        header = Copies.list(header);
        sections = Copies.list(sections);
        footer = Copies.list(footer);
    }
}
