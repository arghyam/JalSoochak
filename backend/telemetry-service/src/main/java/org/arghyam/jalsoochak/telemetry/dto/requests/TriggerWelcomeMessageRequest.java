package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class TriggerWelcomeMessageRequest {
    @JsonAlias({"contactId", "phone", "contact"})
    private String phoneNumber;
    private Boolean isSingleTenant;

    public String resolvePhoneNumber() {
        return phoneNumber == null ? "" : phoneNumber.trim();
    }

    public boolean resolveSingleTenant() {
        return Boolean.TRUE.equals(isSingleTenant);
    }
}
