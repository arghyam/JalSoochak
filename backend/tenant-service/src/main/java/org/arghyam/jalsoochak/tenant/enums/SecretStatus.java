package org.arghyam.jalsoochak.tenant.enums;

/**
 * MESSAGING-PROVIDER-SECRETS: whether a secret has a stored value.
 *
 * <p>This is the only thing a read of the secret store ever returns. Secrets are
 * write-only through the API: there is no endpoint, and no response shape, that can
 * hand back a value once it has been written.
 */
public enum SecretStatus {

    /** A value is stored and encrypted under the tenant's current key. */
    SET,

    /** No value is stored, so the tenant falls back to the system default provider. */
    MISSING
}
