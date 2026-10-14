package org.arghyam.jalsoochak.message.channel.provider;

import org.arghyam.jalsoochak.message.dto.MailRequest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Port interface for transactional email delivery.
 *
 * <p>Implementations are plain classes, one instance per email account. The
 * {@code notification.mail.provider} property selects the <em>system default</em>
 * one, built in {@code SystemDefaultProviders}:
 * <ul>
 *   <li>{@code sendgrid} — {@link SendGridMailSender} (default)</li>
 *   <li>{@code smtp}     — {@link SmtpMailSender}</li>
 * </ul>
 *
 * <p>PER-TENANT-PROVIDERS: a tenant that has configured its own account gets its
 * own instance of the same adapter instead, built by the matching
 * {@link EmailSenderFactory} and handed out by {@link TenantChannelProviders}
 * (O2-1, O2-2). Callers ask that class for a tenant's sender and otherwise
 * depend only on this port, so no business logic changes.
 *
 * <p>To add a provider, write an {@code EmailSender} adapter and an
 * {@code EmailSenderFactory} for it, and register the factory as a
 * {@code @Component}. Provider-specific concerns (auth scheme, template
 * addressing, request and response shape) stay inside the adapter.
 *
 * <p>Implementations throw {@link RuntimeException} on delivery failure so that
 * callers (e.g. {@code NotificationEventRouter}) can route the Kafka message
 * to the dead-letter topic without swallowing the error.
 *
 * <p>DELIVERY-LEDGER: a successful send returns what the provider handed back, so the ledger can match
 * the provider's later delivery reports to it. An adapter that can be asked about a message
 * afterwards also implements {@link #lookupStatuses}.
 */
public interface EmailSender {

    /**
     * Send a transactional email.
     *
     * @param request fully-populated mail request
     * @return the provider's id and word for the accepted message; never a delivery confirmation
     * @throws RuntimeException if delivery fails for any reason
     */
    ProviderAcceptance send(MailRequest request);

    /**
     * The provider this adapter sends through, as recorded in the delivery ledger — a short lower-case
     * identifier that stays stable across releases.
     */
    String providerId();

    /** Whether {@link #lookupStatuses} can actually ask the provider anything. */
    default boolean supportsStatusLookup() {
        return false;
    }

    /**
     * Asks the provider what became of messages this account sent.
     *
     * @param providerMessageIds ids returned by {@link #send}
     * @param sentFrom           the earliest send among them, so a provider queried by time window
     *                           can be asked for no more than necessary
     * @param sentTo             the latest send among them
     * @return a report for each message the provider had something to say about; messages it did not
     *         mention are simply absent. Never throws: a failed lookup returns what it has.
     */
    default List<DeliveryReceipt> lookupStatuses(Collection<String> providerMessageIds, Instant sentFrom,
                                                 Instant sentTo) {
        return List.of();
    }
}
