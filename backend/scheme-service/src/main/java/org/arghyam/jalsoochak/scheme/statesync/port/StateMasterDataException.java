package org.arghyam.jalsoochak.scheme.statesync.port;

/** The upstream could not be read: transport failure, rejected credentials, or an unreadable payload. */
public class StateMasterDataException extends RuntimeException {

    public StateMasterDataException(String message) {
        super(message);
    }

    public StateMasterDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
