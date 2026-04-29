package org.arghyam.jalsoochak.message.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TriggerWelcomeMessageRequest {
    @NotBlank(message = "phoneNumber is required")
    private String phoneNumber;
}
