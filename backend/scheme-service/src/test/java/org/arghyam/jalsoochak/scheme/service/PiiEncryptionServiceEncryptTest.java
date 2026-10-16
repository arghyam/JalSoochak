package org.arghyam.jalsoochak.scheme.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The write half added for the state sync: encrypt and the title blind index. */
class PiiEncryptionServiceEncryptTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final PiiEncryptionService pii = new PiiEncryptionService(KEY_A, KEY_B);

    @Test
    void encryptsTrimmedTextThatDecryptsBack() {
        String cipher = pii.encrypt("  919100000001 ");

        assertThat(cipher).isNotEqualTo(pii.encrypt("919100000001")); // random IV
        assertThat(pii.decrypt(cipher)).isEqualTo("919100000001");
        assertThat(pii.isEnabled()).isTrue();
    }

    @Test
    void neverFallsBackToPlaintextWithoutKeys() {
        PiiEncryptionService disabled = new PiiEncryptionService("", "");

        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.encrypt("919100000001")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashesTitlesTheWayUserServiceSearchesThem() {
        assertThat(pii.titleHash("  Thagen Saikia ")).isEqualTo(pii.hmac("thagen saikia".toLowerCase(Locale.ROOT)));
    }
}
