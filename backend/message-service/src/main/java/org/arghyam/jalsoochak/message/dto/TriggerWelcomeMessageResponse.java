package org.arghyam.jalsoochak.message.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Response of {@code POST /api/v1/message/trigger-welcome-message}.
 *
 * <p>Deliberately carries no operator PII. {@code name}, {@code phoneNumber} and {@code state}
 * used to be here and told the caller who a phone number belongs to and where they work; since the
 * endpoint resolves the tenant by probing every schema for the phone, that made the response an
 * identity lookup rather than an acknowledgement. {@code contactId} is the Glific handle a support
 * engineer needs to chase a delivery, and {@code tenantCode} says which tenant answered; neither
 * names anyone. The caller supplied the phone number, so echoing it back told them nothing they
 * did not already have.
 */
@Value
@Builder
public class TriggerWelcomeMessageResponse {
    boolean success;
    String tenantCode;
    Long contactId;
    String message;
}
