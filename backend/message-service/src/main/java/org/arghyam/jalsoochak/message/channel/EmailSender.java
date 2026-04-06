package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.MailRequest;

/**
 * Port interface for transactional email delivery.
 *
 * <p>Exactly one implementation is registered in the Spring context at a time,
 * controlled by the {@code notification.mail.provider} property:
 * <ul>
 *   <li>{@code sendgrid} — {@link SendGridMailSender} (default)</li>
 *   <li>{@code smtp}     — {@link SmtpMailSender}</li>
 * </ul>
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
