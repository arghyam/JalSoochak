package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class TriggerWelcomeMessageRequest {
    @JsonAlias({"contactId", "phone", "contact"})
    private String phoneNumber;

    public String resolvePhoneNumber() {
        return phoneNumber == null ? "" : phoneNumber.trim();
    }
}
