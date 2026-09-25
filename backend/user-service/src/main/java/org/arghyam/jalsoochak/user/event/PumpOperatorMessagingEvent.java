package org.arghyam.jalsoochak.user.event;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String whatsappLanguageId;
    private List<String> pumpOperatorPhones;

    /**
     * Emits {@link #whatsappLanguageId} under its legacy name as well, so a consumer that has not
     * yet been upgraded still reads it.
     *
     * @deprecated read {@link #getWhatsappLanguageId()}; the legacy key is dropped once every
     *             consumer reads {@code whatsappLanguageId}.
     */
    @Deprecated(forRemoval = true)
    @JsonProperty("glificLanguageId")
    public String getGlificLanguageId() {
        return whatsappLanguageId;
    }
}
