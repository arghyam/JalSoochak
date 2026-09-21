package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.MailRequest;

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
 */
public interface EmailSender {

    /**
     * Send a transactional email.
     *
     * @param request fully-populated mail request
     * @throws RuntimeException if delivery fails for any reason
     */
    void send(MailRequest request);
}
