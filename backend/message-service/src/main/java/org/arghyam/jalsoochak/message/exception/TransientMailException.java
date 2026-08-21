package org.arghyam.jalsoochak.message.exception;

/**
 * The provider could not accept the message, but the same request may well succeed later —
 * a 429 rate limit, a 5xx, or a connection that never reached the provider at all.
 *
 * <p>Nothing was delivered, so replaying the event cannot duplicate an email. Handlers rethrow
 * this so the Kafka container applies its back-off ladder and, once exhausted, dead-letters the
 * record to {@code common-topic.DLT}.
 */
public class TransientMailException extends MailDeliveryException {

    public TransientMailException(String message, Throwable cause) {
        super(message, cause);
    }
}
