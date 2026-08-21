package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.exception.PermanentMailException;
import org.arghyam.jalsoochak.message.exception.TransientMailException;

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
 * <p><strong>Failure contract.</strong> Adapters must classify their own errors, because only
 * the adapter knows what a given vendor's status codes mean. Callers decide what to do from the
 * exception type alone and never inspect vendor detail:
 * <ul>
 *   <li>returns normally — the provider accepted the message;</li>
 *   <li>{@link TransientMailException} — nothing was delivered and a replay may succeed
 *       (429, 5xx, connection failure). Callers rethrow so the Kafka container retries.</li>
 *   <li>{@link PermanentMailException} — replaying cannot help (4xx, bad credentials), or the
 *       outcome is ambiguous and a replay risks a duplicate (timeout). Callers dead-letter it.</li>
 * </ul>
 *
 * <p>This mirrors the three-state contract {@link SmsSender} already carries. A new adapter that
 * throws neither type is treated as permanent by callers — the safe default, since a duplicate
 * email is worse than a delayed one.
 */
public interface EmailSender {

    /**
     * Send a transactional email.
     *
     * @param request fully-populated mail request
     * @throws TransientMailException if delivery failed in a way a retry could fix
     * @throws PermanentMailException if a retry cannot help, or the outcome is ambiguous
     */
    void send(MailRequest request);
}
