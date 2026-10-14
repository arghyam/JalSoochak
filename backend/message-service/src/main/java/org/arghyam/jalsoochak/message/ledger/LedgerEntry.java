package org.arghyam.jalsoochak.message.ledger;

import lombok.Builder;

import java.time.LocalDate;
import java.util.Map;

/**
 * Everything known about a message before it is sent: who it is for, what it is, and which provider
 * it is going through. {@link NotificationLedger#open} turns it into a row.
 *
 * <p>{@code recipient} is the raw phone number or email address. It is held only in memory, to be
 * hashed: the ledger never stores, logs or publishes it, and {@link #toString()} leaves it out so an
 * entry can be logged safely.</p>
 *
 * @param tenantSchema  the recipient's tenant schema, or {@code null} for a send that belongs to no
 *                      tenant, which is recorded in {@code common_schema}
 * @param tenantId      the tenant's id, when known; resolved from the schema otherwise
 * @param type          what the message is for
 * @param channel       what it goes out on
 * @param provider      the sending adapter's {@code providerId()}
 * @param userId        the recipient's {@code user_table} id, when they have one
 * @param adminUserId   the recipient's {@code tenant_admin_user_master_table} id, when they are an admin user
 * @param userType      the recipient's role, e.g. {@code SECTION_OFFICER}, when known
 * @param recipient     the raw phone number or email address, hashed before it goes anywhere
 * @param contactRef    the provider's own id for the recipient (a WhatsApp contact id); not an address
 * @param templateRef   the provider template or flow the message uses, when known before the send
 * @param correlationId the run the message belongs to
 * @param subjectDate   the day the content is about
 * @param eventType     the Kafka event type that caused the send
 * @param metadata      send metadata for {@code message_blob}; only allow-listed keys are kept
 */
@Builder(toBuilder = true)
public record LedgerEntry(String tenantSchema,
                          Integer tenantId,
                          NotificationType type,
                          LedgerChannel channel,
                          String provider,
                          Long userId,
                          Long adminUserId,
                          String userType,
                          String recipient,
                          String contactRef,
                          String templateRef,
                          String correlationId,
                          LocalDate subjectDate,
                          String eventType,
                          Map<String, Object> metadata) {

    /** Identity only: never the recipient. */
    @Override
    public String toString() {
        return "LedgerEntry[type=" + type + ", channel=" + channel + ", provider=" + provider
                + ", schema=" + tenantSchema + ", userId=" + userId + ", adminUserId=" + adminUserId + "]";
    }
}
