package org.arghyam.jalsoochak.scheme.service;

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
 * AES-256-GCM encryption + HMAC-SHA256 helpers for tenant PII columns (phone/email/name).
 * Keys are supplied via env vars (base64 32-byte):
 * - PII_ENCRYPTION_KEY -> pii.encryption-key
 * - PII_HMAC_KEY       -> pii.hmac-key
 *
 * This implementation mirrors telemetry-service's PiiEncryptionService to keep behavior consistent.
 */
@Service
public class PiiEncryptionService {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec aesKey;
    private final byte[] hmacKeyBytes;
    private final boolean enabled;
    private final SecureRandom rng = new SecureRandom();

    public PiiEncryptionService(
            @Value("${pii.encryption-key:}") String encodedAesKey,
            @Value("${pii.hmac-key:}") String encodedHmacKey
    ) {
        if (encodedAesKey == null || encodedAesKey.isBlank() || encodedHmacKey == null || encodedHmacKey.isBlank()) {
            // Run in "disabled" mode when keys are not configured (local dev / tests).
            this.aesKey = null;
            this.hmacKeyBytes = null;
            this.enabled = false;
            return;
        }

        byte[] aesBytes = Base64.getDecoder().decode(encodedAesKey);
        byte[] hmacBytes = Base64.getDecoder().decode(encodedHmacKey);

        if (aesBytes.length != 32) {
            throw new IllegalStateException("PII_ENCRYPTION_KEY must decode to exactly 32 bytes (256 bits)");
        }
        if (hmacBytes.length != 32) {
            throw new IllegalStateException("PII_HMAC_KEY must decode to exactly 32 bytes (256 bits)");
        }

        this.aesKey = new SecretKeySpec(aesBytes, "AES");
        this.hmacKeyBytes = hmacBytes;
        this.enabled = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> Arrays.fill(hmacKeyBytes, (byte) 0)));
    }

    public String decrypt(String encoded) {
        if (!enabled) {
            return null;
        }
        if (encoded == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Ciphertext too short: " + decoded.length);
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
     * Decrypts a value read from DB with a legacy plaintext fallback.
     */
    public String safeDecrypt(String encoded) {
        if (!enabled) {
            return null;
        }
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

    public String hmac(String plaintext) {
        if (!enabled) {
            return null;
        }
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
