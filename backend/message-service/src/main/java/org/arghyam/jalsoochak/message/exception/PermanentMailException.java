package org.arghyam.jalsoochak.message.exception;

/**
 * The request will fail the same way however many times it is replayed — a rejected API key,
 * a malformed payload, an address the provider refuses.
 *
 * <p>Also covers the <em>ambiguous</em> case of a timeout: the provider may or may not have
 * accepted the message before the client gave up, and retrying an accepted send would deliver
 * the recipient a second copy. Treating it as permanent keeps duplicates at zero and leaves the
 * record on {@code account-email-dlt} for a human to judge.
 *
 * <p>Handlers route this to the dead-letter topic and return normally — retrying only burns the
 * shared listener thread.
 */
public class PermanentMailException extends MailDeliveryException {

    public PermanentMailException(String message, Throwable cause) {
        super(message, cause);
    }
}
