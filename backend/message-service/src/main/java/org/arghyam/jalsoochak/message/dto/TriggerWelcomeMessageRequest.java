package org.arghyam.jalsoochak.message.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class TriggerWelcomeMessageRequest {
    /**
     * Glific commonly posts sender phone as "contactId" (e.g. @contact.phone).
     * We also accept "phoneNumber" for direct API callers.
     */
    @JsonAlias({"contactId", "phone", "contact"})
    private String phoneNumber;

    public String resolvePhoneNumber() {
        return phoneNumber == null ? "" : phoneNumber.trim();
    }
}
