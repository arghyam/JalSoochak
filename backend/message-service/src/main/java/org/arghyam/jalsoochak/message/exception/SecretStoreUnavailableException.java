package org.arghyam.jalsoochak.message.exception;

/**
 * Raised when a secret-store operation is attempted on a deployment that has no master key
 * configured.
 *
 * <p>PER-TENANT-PROVIDERS: the read-side twin of tenant-service's
 * {@code exception/SecretStoreUnavailableException}, kept identical apart from the package and
 * this paragraph. There it maps to {@code 503} on an API call; here there is no caller to answer,
 * because {@code PerTenantProviderStartupValidator} refuses to start the service when the feature
 * flag is on and no key is configured. It remains reachable only if the flag is switched on at
 * runtime through a means the validator cannot see, in which case it is caught as a build failure
 * and the tenant falls back to the system default.
 */
public class SecretStoreUnavailableException extends RuntimeException {

    public SecretStoreUnavailableException(String message) {
        super(message);
    }
}
