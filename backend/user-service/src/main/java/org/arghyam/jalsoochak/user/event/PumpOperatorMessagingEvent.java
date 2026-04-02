package org.arghyam.jalsoochak.user.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Event contract consumed by message-service for pump operator onboarding workflows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PumpOperatorMessagingEvent {
    private String eventType; // UPDATE_USER_LANGUAGE | SEND_WELCOME_MESSAGE | SEND_WELCOME_MESSAGE_ADMIN
    private String tenantCode;
    private Integer tenantId;
    private String triggeredAt; // ISO-8601 UTC timestamp, e.g. 2026-03-11T10:00:00.000Z
    private String glificLanguageId;
    private List<String> pumpOperatorPhones;
}
