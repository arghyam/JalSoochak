package org.arghyam.jalsoochak.tenant.dto.internal.reportlayout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** A line of the report footer, below the last section. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FooterElementDTO.Note.class, name = "NOTE"),
        @JsonSubTypes.Type(value = FooterElementDTO.SupportContact.class, name = "SUPPORT_CONTACT"),
        @JsonSubTypes.Type(value = SpacerDTO.class, name = "SPACER")
})
public sealed interface FooterElementDTO
        permits FooterElementDTO.Note, FooterElementDTO.SupportContact, SpacerDTO {

    /** A footnote-sized line of free text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Note(String text) implements FooterElementDTO {
    }

    /** A footnote-sized line that must carry {@code {supportPhone}}; left out when the phone is blank. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SupportContact(String text) implements FooterElementDTO {
    }
}
