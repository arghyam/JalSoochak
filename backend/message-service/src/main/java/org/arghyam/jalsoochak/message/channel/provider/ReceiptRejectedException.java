package org.arghyam.jalsoochak.message.channel.provider;

/** A pushed delivery report that could not be shown to come from its provider. Answered with 401. */
public class ReceiptRejectedException extends RuntimeException {

    public ReceiptRejectedException(String message) {
        super(message);
    }
}
