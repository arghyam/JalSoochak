package org.arghyam.jalsoochak.analytics.exception;

/**
 * An event whose payload cannot be processed as sent. Retrying cannot fix it, so the Kafka error
 * handler sends it straight to the dead-letter topic.
 */
public class MalformedEventException extends RuntimeException {
    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
