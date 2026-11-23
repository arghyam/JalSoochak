package org.arghyam.jalsoochak.message.report.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A line of the report header, above the first section. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HeaderElement.Title.class, name = "TITLE"),
        @JsonSubTypes.Type(value = HeaderElement.Subtitle.class, name = "SUBTITLE"),
        @JsonSubTypes.Type(value = HeaderElement.Field.class, name = "FIELD"),
        @JsonSubTypes.Type(value = Spacer.class, name = "SPACER"),
        @JsonSubTypes.Type(value = HeaderElement.DashboardLink.class, name = "DASHBOARD_LINK")
})
public sealed interface HeaderElement
        permits HeaderElement.Title, HeaderElement.Subtitle, HeaderElement.Field, Spacer,
        HeaderElement.DashboardLink {

    /** The bold, centred report title. */
    record Title(String text) implements HeaderElement {
    }

    /** A smaller centred line under the title. */
    record Subtitle(String text) implements HeaderElement {
    }

    /** {@code label} in bold followed by {@code value}, e.g. {@code Date: 19 July 2026}. */
    record Field(String label, String value) implements HeaderElement {
    }

    /**
     * {@code prefix + <dashboard URL as a link> + suffix}; left out when the URL is blank.
     *
     * @param prefix never {@code null}; empty when absent
     * @param suffix never {@code null}; empty when absent
     */
    record DashboardLink(String prefix, String suffix) implements HeaderElement {

        public DashboardLink {
            prefix = prefix == null ? "" : prefix;
            suffix = suffix == null ? "" : suffix;
        }
    }
}
