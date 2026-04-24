package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiEncryptionServiceTest {

    private static final String AES_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String HMAC_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptDecryptAndHmacWorkForTrimmedInput() {
        PiiEncryptionService service = new PiiEncryptionService(AES_KEY, HMAC_KEY);

        String encrypted = service.encrypt("  9876543210  ");

        assertThat(encrypted).isNotBlank();
        assertThat(service.decrypt(encrypted)).isEqualTo("9876543210");

        String hmacOne = service.hmac("  9876543210 ");
        String hmacTwo = service.hmac("9876543210");
        assertThat(hmacOne).hasSize(64);
        assertThat(hmacOne).isEqualTo(hmacTwo);
    }

    @Test
    void nullInputPathsReturnNull() {
        PiiEncryptionService service = new PiiEncryptionService(AES_KEY, HMAC_KEY);

        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
        assertThat(service.safeDecrypt(null)).isNull();
        assertThat(service.hmac(null)).isNull();
    }

    @Test
    void decryptRejectsTooShortCiphertext() {
        PiiEncryptionService service = new PiiEncryptionService(AES_KEY, HMAC_KEY);
        String tooShort = Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> service.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ciphertext too short");
    }

    @Test
    void safeDecryptFallsBackForLegacyPlaintextAndShortBase64() {
        PiiEncryptionService service = new PiiEncryptionService(AES_KEY, HMAC_KEY);

        assertThat(service.safeDecrypt("plain-value")).isEqualTo("plain-value");

        String shortBase64 = Base64.getEncoder().encodeToString(new byte[4]);
        assertThat(service.safeDecrypt(shortBase64)).isEqualTo(shortBase64);
    }

    @Test
    void constructorValidatesKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new PiiEncryptionService(shortKey, HMAC_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII_ENCRYPTION_KEY");

        assertThatThrownBy(() -> new PiiEncryptionService(AES_KEY, shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PII_HMAC_KEY");
    }
}
