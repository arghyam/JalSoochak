package org.arghyam.jalsoochak.message.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TriggerWelcomeMessageResponse {
    boolean success;
    String tenantCode;
    String phoneNumber;
    Long contactId;
    String name;
    String state;
    String message;
}
