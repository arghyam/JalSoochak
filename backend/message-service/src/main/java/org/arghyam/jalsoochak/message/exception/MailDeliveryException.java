package org.arghyam.jalsoochak.message.exception;

/**
 * Base type for failures raised by an {@link org.arghyam.jalsoochak.message.channel.EmailSender}
 * adapter.
 *
 * <p>Callers must not catch this type directly — the whole point of the hierarchy is that
 * {@link TransientMailException} and {@link PermanentMailException} demand different handling.
 * See {@link org.arghyam.jalsoochak.message.channel.EmailSender} for the contract.
 */
public abstract class MailDeliveryException extends RuntimeException {

    protected MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
