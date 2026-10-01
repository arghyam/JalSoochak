package org.arghyam.jalsoochak.tenant.exception;

/**
 * MESSAGING-PROVIDER-SECRETS: raised when a secret-store operation is attempted on a
 * deployment that has no master key configured.
 *
 * <p>Maps to {@code 503}, not {@code 500}: the request is well-formed and would succeed
 * once the environment supplies {@code MESSAGING_SECRET_MASTER_KEY_V<n>}, so the caller
 * should be told the capability is switched off rather than that it broke.
 */
public class SecretStoreUnavailableException extends RuntimeException {

    public SecretStoreUnavailableException(String message) {
        super(message);
    }
}
