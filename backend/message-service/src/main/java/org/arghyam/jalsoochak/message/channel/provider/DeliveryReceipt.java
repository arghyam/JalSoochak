package org.arghyam.jalsoochak.message.channel.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One provider report about one message, in provider-neutral terms — whether it arrived pushed to a
 * webhook or pulled from the provider's API.
 *
 * <p>Carries no address and no message text: adapters lift the identifiers, the status and the failure
 * code out of the provider's payload and leave the rest behind, and the reason is redacted before it
 * gets here.</p>
 *
 * @param providerId        the adapter's {@code providerId()}; with {@code providerMessageId}, the key
 *                          the report is matched on
 * @param providerMessageId the provider's id for the message, as returned at send time
 * @param trackingRef       our own reference, when the provider echoes it back (see
 *                          {@code NotificationLedger}); {@code null} otherwise
 * @param state             the provider's status mapped onto {@link DeliveryState}
 * @param providerStatus    the provider's own word, verbatim
 * @param errorCode         the provider's failure code, when it failed
 * @param errorReason       the provider's failure text, phone numbers redacted
 * @param occurredAt        when the provider says the status was reached, or {@code null} if it does not
 * @param cost              what the provider charged, when the report says
 * @param costCurrency      the currency of {@code cost}
 */
public record DeliveryReceipt(String providerId,
                              String providerMessageId,
                              String trackingRef,
                              DeliveryState state,
                              String providerStatus,
                              String errorCode,
                              String errorReason,
                              Instant occurredAt,
                              BigDecimal cost,
                              String costCurrency) {
}
