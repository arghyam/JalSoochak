package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A line of the report header, above the first section. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HeaderElementDTO.Title.class, name = "TITLE"),
        @JsonSubTypes.Type(value = HeaderElementDTO.Subtitle.class, name = "SUBTITLE"),
        @JsonSubTypes.Type(value = HeaderElementDTO.Field.class, name = "FIELD"),
        @JsonSubTypes.Type(value = SpacerDTO.class, name = "SPACER"),
        @JsonSubTypes.Type(value = HeaderElementDTO.DashboardLink.class, name = "DASHBOARD_LINK")
})
public sealed interface HeaderElementDTO
        permits HeaderElementDTO.Title, HeaderElementDTO.Subtitle, HeaderElementDTO.Field, SpacerDTO,
        HeaderElementDTO.DashboardLink {

    /** The bold, centred report title. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Title(String text) implements HeaderElementDTO {
    }

    /** A smaller centred line under the title. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Subtitle(String text) implements HeaderElementDTO {
    }

    /** {@code label} in bold followed by {@code value}, e.g. {@code Date: 19 July 2026}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Field(String label, String value) implements HeaderElementDTO {
    }

    /** {@code prefix + <dashboard URL as a link> + suffix}; left out when the URL is blank. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DashboardLink(String prefix, String suffix) implements HeaderElementDTO {
    }
}
