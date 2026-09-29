package org.arghyam.jalsoochak.scheme.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiEncryptionServiceTest {

    private static final byte[] AES_KEY = bytes(32, 1);
    private static final byte[] HMAC_KEY = bytes(32, 7);
    private static final String AES_KEY_B64 = Base64.getEncoder().encodeToString(AES_KEY);
    private static final String HMAC_KEY_B64 = Base64.getEncoder().encodeToString(HMAC_KEY);

    private static byte[] bytes(int len, int seed) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }

    private static String encrypt(String plaintext) throws Exception {
        byte[] iv = bytes(12, 42);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
    }

    @Nested
    class Disabled {

        @Test
        void blankOrMissingKeys_disableEveryOperation() {
            PiiEncryptionService[] services = {
                    new PiiEncryptionService(null, HMAC_KEY_B64),
                    new PiiEncryptionService("  ", HMAC_KEY_B64),
                    new PiiEncryptionService(AES_KEY_B64, null),
                    new PiiEncryptionService(AES_KEY_B64, "")
            };
            for (PiiEncryptionService service : services) {
                assertThat(service.decrypt("anything")).isNull();
                assertThat(service.safeDecrypt("anything")).isNull();
                assertThat(service.hmac("anything")).isNull();
            }
        }
    }

    @Nested
    class Constructor {

        @Test
        void rejectsAesKeyOfWrongLength() {
            String shortKey = Base64.getEncoder().encodeToString(bytes(16, 0));
            assertThatThrownBy(() -> new PiiEncryptionService(shortKey, HMAC_KEY_B64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PII_ENCRYPTION_KEY");
        }

        @Test
        void rejectsHmacKeyOfWrongLength() {
            String shortKey = Base64.getEncoder().encodeToString(bytes(16, 0));
            assertThatThrownBy(() -> new PiiEncryptionService(AES_KEY_B64, shortKey))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PII_HMAC_KEY");
        }
    }

    @Nested
    class Enabled {

        private final PiiEncryptionService service = new PiiEncryptionService(AES_KEY_B64, HMAC_KEY_B64);

        @Test
        void decrypt_roundTripsCiphertext() throws Exception {
            assertThat(service.decrypt(encrypt("Ramesh Kumar"))).isEqualTo("Ramesh Kumar");
        }

        @Test
        void decrypt_returnsNullForNull() {
            assertThat(service.decrypt(null)).isNull();
        }

        @Test
        void decrypt_rejectsCiphertextShorterThanIv() {
            String tooShort = Base64.getEncoder().encodeToString(bytes(12, 0));
            assertThatThrownBy(() -> service.decrypt(tooShort))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ciphertext too short");
        }

        @Test
        void decrypt_wrapsCipherFailures() {
            String garbage = Base64.getEncoder().encodeToString(bytes(40, 3));
            assertThatThrownBy(() -> service.decrypt(garbage))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AES-GCM decryption failed")
                    .hasCauseInstanceOf(Exception.class);
        }

        @Test
        void decrypt_wrapsInvalidBase64() {
            assertThatThrownBy(() -> service.decrypt("not base64 !!"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AES-GCM decryption failed");
        }

        @Test
        void safeDecrypt_decryptsCiphertext() throws Exception {
            assertThat(service.safeDecrypt(encrypt("a@b.com"))).isEqualTo("a@b.com");
        }

        @Test
        void safeDecrypt_returnsNullForNull() {
            assertThat(service.safeDecrypt(null)).isNull();
        }

        @Test
        void safeDecrypt_returnsLegacyPlaintextWhenNotBase64() {
            assertThat(service.safeDecrypt("Legacy Name")).isEqualTo("Legacy Name");
        }

        @Test
        void safeDecrypt_returnsValueWhenDecodedTooShortForCiphertext() {
            // "919999999999" is valid base64 but decodes to fewer than IV + tag bytes.
            assertThat(service.safeDecrypt("919999999999")).isEqualTo("919999999999");
        }

        @Test
        void hmac_isDeterministicHexAndTrimsInput() {
            String digest = service.hmac("919999999999");
            assertThat(digest).hasSize(64).matches("[0-9a-f]+");
            assertThat(service.hmac("  919999999999 ")).isEqualTo(digest);
            assertThat(service.hmac("918888888888")).isNotEqualTo(digest);
        }

        @Test
        void hmac_returnsNullForNull() {
            assertThat(service.hmac(null)).isNull();
        }
    }
}
