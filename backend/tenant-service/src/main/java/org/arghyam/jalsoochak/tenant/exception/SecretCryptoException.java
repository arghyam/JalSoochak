package org.arghyam.jalsoochak.tenant.exception;

/**
 * MESSAGING-PROVIDER-SECRETS: an encryption or decryption failure in the tenant secret
 * store — a wrong master key, a tampered or truncated ciphertext, or an authentication
 * tag that does not match the row the ciphertext was read from.
 *
 * <p>There is deliberately no plaintext fallback: unlike {@code PiiEncryptionService},
 * which returns a legacy column as-is when it fails to decrypt, a secret that cannot be
 * decrypted must surface as a failure. Silently returning ciphertext would hand a
 * provider a "password" that is really a base64 blob.
 *
 * <p>Messages name the row only — tenant, channel, secret name, key version. Never a
 * secret value, never key material. Handled by the generic {@code RuntimeException}
 * mapping, so the response body carries no detail at all.
 */
public class SecretCryptoException extends RuntimeException {

    public SecretCryptoException(String message) {
        super(message);
    }

    public SecretCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
