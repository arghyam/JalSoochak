package org.arghyam.jalsoochak.telemetry.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * {@code UPDATE_USER_LANGUAGE} on {@code common-topic}: message-service sets {@code whatsappLanguageId}
 * on the WhatsApp contact of each phone. The same contract user-service publishes on a pump-operator
 * upload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLanguageUpdateEvent {

    private String eventType;
    /** The tenant schema's code, e.g. {@code AS} for {@code tenant_as}. */
    private String tenantCode;
    private Integer tenantId;
    /** ISO-8601 UTC timestamp. */
    private String triggeredAt;
    /** The language's {@code common_schema.language_master} id, which the WhatsApp provider uses. */
    private Integer whatsappLanguageId;
    private List<String> pumpOperatorPhones;
}
