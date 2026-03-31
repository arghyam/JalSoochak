package org.arghyam.jalsoochak.telemetry.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Application-layer PII encryption service.
 *
 * <p><b>Encryption:</b> AES-256-GCM with a random 12-byte IV per value.
 * Ciphertext format: {@code base64(iv[12] || ciphertext+tag)}.
 * The 128-bit auth tag is appended by the JCE provider automatically.
 *
 * <p><b>HMAC:</b> HMAC-SHA256 keyed by a separate secret ({@code PII_HMAC_KEY}).
 * Used for exact-match DB lookups on encrypted fields (e.g. phone number deduplication).
 * A keyed HMAC is required here because phone numbers have a small search space
 * (~10B Indian numbers), making plain SHA-256 reversible via brute force.
 *
 * <p><b>Key injection:</b> Both keys are base64-encoded 32-byte (256-bit) secrets
 * supplied via environment variables {@code PII_ENCRYPTION_KEY} and {@code PII_HMAC_KEY}.
 * Never commit key material to source control.
 */
@Service
public class PiiEncryptionService {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private SecretKeySpec aesKey;
    private byte[] aesKeyBytes;
    private byte[] hmacKeyBytes;
    private final SecureRandom rng = new SecureRandom();

    public PiiEncryptionService(
            @Value("${pii.encryption-key}") String encodedAesKey,
            @Value("${pii.hmac-key}") String encodedHmacKey) {

        byte[] aesBytes = Base64.getDecoder().decode(encodedAesKey);
        byte[] hmacBytes = Base64.getDecoder().decode(encodedHmacKey);

        if (aesBytes.length != 32) {
            throw new IllegalStateException("PII_ENCRYPTION_KEY must decode to exactly 32 bytes (256 bits)");
        }
        if (hmacBytes.length != 32) {
            throw new IllegalStateException("PII_HMAC_KEY must decode to exactly 32 bytes (256 bits)");
        }

        this.aesKey = new SecretKeySpec(aesBytes, "AES");
        this.aesKeyBytes = aesBytes;
        this.hmacKeyBytes = hmacBytes;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Arrays.fill(aesKeyBytes, (byte) 0);
            Arrays.fill(hmacKeyBytes, (byte) 0);
            aesKeyBytes = null;
            hmacKeyBytes = null;
            aesKey = null;
        }));
    }

    /**
     * Encrypts {@code plaintext} with AES-256-GCM.
     *
     * @return {@code base64(iv || ciphertext+tag)}, or {@code null} if input is {@code null}
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        plaintext = plaintext.trim();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext.getBytes(UTF_8));

            byte[] output = new byte[IV_LENGTH_BYTES + ciphertextAndTag.length];
            System.arraycopy(iv, 0, output, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertextAndTag, 0, output, IV_LENGTH_BYTES, ciphertextAndTag.length);

            return Base64.getEncoder().encodeToString(output);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}.
     *
     * @return plaintext, or {@code null} if input is {@code null}
     * @throws IllegalStateException if decryption fails (tampered ciphertext, wrong key)
     */
    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "Ciphertext too short: " + decoded.length + " bytes (expected > " + IV_LENGTH_BYTES + ")");
            }
            byte[] iv = Arrays.copyOfRange(decoded, 0, IV_LENGTH_BYTES);
            byte[] ciphertextAndTag = Arrays.copyOfRange(decoded, IV_LENGTH_BYTES, decoded.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertextAndTag);

            return new String(plaintext, UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    /**
     * Decrypts a PII column value read from the database, with a fallback for
     * legacy rows stored as plaintext before encryption.
     *
     * <p>A value is treated as legacy plaintext when it is either not valid Base64 or its
     * decoded length is less than 28 bytes (12-byte IV + 16-byte GCM auth tag — the
     * minimum AES-GCM payload for empty plaintext).
     *
     * @return decrypted plaintext, raw value for legacy rows, or {@code null} if input is {@code null}
     */
    public String safeDecrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length < IV_LENGTH_BYTES + 16) {
                return encoded;
            }
        } catch (IllegalArgumentException e) {
            return encoded;
        }
        return decrypt(encoded);
    }

    /**
     * Returns a hex-encoded HMAC-SHA256 of the trimmed {@code plaintext}.
     * Used to produce a deterministic lookup key for encrypted fields.
     *
     * @return 64-char lowercase hex string, or {@code null} if input is {@code null}
     */
    public String hmac(String plaintext) {
        if (plaintext == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKeyBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(plaintext.trim().getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
