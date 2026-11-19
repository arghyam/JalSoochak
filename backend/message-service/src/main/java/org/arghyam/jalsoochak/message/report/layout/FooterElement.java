package org.arghyam.jalsoochak.message.report.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A line of the report footer, below the last section. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FooterElement.Note.class, name = "NOTE"),
        @JsonSubTypes.Type(value = FooterElement.SupportContact.class, name = "SUPPORT_CONTACT"),
        @JsonSubTypes.Type(value = Spacer.class, name = "SPACER")
})
public sealed interface FooterElement permits FooterElement.Note, FooterElement.SupportContact, Spacer {

    /** A footnote-sized line of free text. */
    record Note(String text) implements FooterElement {
    }

    /**
     * A footnote-sized line carrying {@code {supportPhone}}; left out when the phone is blank.
     */
    record SupportContact(String text) implements FooterElement {
    }
}
