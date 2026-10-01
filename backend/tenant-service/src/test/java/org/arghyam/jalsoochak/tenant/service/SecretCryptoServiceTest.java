package org.arghyam.jalsoochak.tenant.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.arghyam.jalsoochak.tenant.config.properties.MessagingSecretProperties;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.exception.SecretCryptoException;
import org.arghyam.jalsoochak.tenant.exception.SecretStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * MESSAGING-PROVIDER-SECRETS: unit tests for {@link SecretCryptoService}.
 *
 * <p>Plain JUnit, no Spring context — the service's only collaborator is a properties object.
 *
 * <p>These tests stand in for the guarantees a crypto library would have enforced. The
 * scheme was implemented on plain JCE deliberately, so the two things that would otherwise
 * go unchecked are asserted here explicitly: that a fresh nonce is drawn on <em>every</em>
 * encryption, and that the AAD binds each ciphertext to exactly one row.
 */
@DisplayName("SecretCryptoService Tests")
class SecretCryptoServiceTest {

    private static final int TENANT_A = 7;
    private static final int TENANT_B = 8;
    private static final int KEY_VERSION = 1;
    private static final String MASTER_V1 = "v1";
    private static final String SECRET_VALUE = "SG.aVeryRealLookingSendGridApiKey.0123456789abcdef";

    private String keyV1;
    private SecretCryptoService service;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecretCryptoService serviceWith(String activeId, Map<String, String> keys) {
        MessagingSecretProperties props = new MessagingSecretProperties();
        props.setActiveMasterKeyId(activeId);
        props.setMasterKeys(keys);
        return new SecretCryptoService(props);
    }

    @BeforeEach
    void setUp() {
        keyV1 = randomKey();
        service = serviceWith(MASTER_V1, new LinkedHashMap<>(Map.of(MASTER_V1, keyV1)));
    }

    /** A wrapped-then-unwrapped data key for tenant A, version 1. */
    private byte[] dataKeyFor(SecretCryptoService svc, int tenantId, int keyVersion) {
        byte[] dataKey = svc.generateDataKey();
        String wrapped = svc.wrapDataKey(dataKey, tenantId, keyVersion, MASTER_V1);
        return svc.unwrapDataKey(wrapped, tenantId, keyVersion, MASTER_V1);
    }

    // ── startup validation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Startup validation")
    class StartupValidation {

        @Test
        @DisplayName("A key that does not decode to 32 bytes is rejected at startup")
        void shortKeyRejected() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> serviceWith(MASTER_V1, Map.of(MASTER_V1, shortKey)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("master-keys.v1")
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("A key that is not valid base64 is rejected without quoting the value")
        void nonBase64KeyRejected() {
            assertThatThrownBy(() -> serviceWith(MASTER_V1, Map.of(MASTER_V1, "not!valid!base64!")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not valid base64")
                    // The offending input is key material as far as we know; it must not be echoed,
                    // and Base64's own exception message would have quoted it.
                    .hasMessageNotContaining("not!valid!base64!");
        }

        @Test
        @DisplayName("An active key id that is not configured is rejected at startup")
        void unknownActiveKeyIdRejected() {
            assertThatThrownBy(() -> serviceWith("v2", Map.of(MASTER_V1, randomKey())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active-master-key-id 'v2'")
                    .hasMessageContaining("[v1]");
        }

        @Test
        @DisplayName("Configured keys with no active id are rejected at startup")
        void missingActiveKeyIdRejected() {
            assertThatThrownBy(() -> serviceWith(null, Map.of(MASTER_V1, randomKey())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active-master-key-id must be set");
        }

        @Test
        @DisplayName("An active id with no keys at all is rejected at startup")
        void activeIdWithoutKeysRejected() {
            assertThatThrownBy(() -> serviceWith(MASTER_V1, Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no master key is configured");
        }

        @Test
        @DisplayName("No configuration at all starts successfully but disabled")
        void noConfigurationIsDisabledNotFatal() {
            SecretCryptoService disabled = serviceWith("", Map.of());

            assertThat(disabled.isConfigured()).isFalse();
            assertThat(disabled.getReadableMasterKeyIds()).isEmpty();
            assertThatThrownBy(disabled::requireConfigured)
                    .isInstanceOf(SecretStoreUnavailableException.class)
                    .hasMessageContaining("no master key is configured");
            assertThatThrownBy(disabled::generateDataKey)
                    .isInstanceOf(SecretStoreUnavailableException.class);
            assertThatThrownBy(disabled::getActiveMasterKeyId)
                    .isInstanceOf(SecretStoreUnavailableException.class);
        }

        @Test
        @DisplayName("A blank key value is treated as absent, not as a misconfiguration")
        void blankKeyValueIgnored() {
            // This is the shape an unset ${MESSAGING_SECRET_MASTER_KEY_V1:} placeholder binds to.
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put(MASTER_V1, "");
            SecretCryptoService disabled = serviceWith("", keys);

            assertThat(disabled.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("Several master keys stay readable while one is active")
        void multipleKeysReadable() {
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("v1", randomKey());
            keys.put("v2", randomKey());
            SecretCryptoService rotating = serviceWith("v2", keys);

            assertThat(rotating.getActiveMasterKeyId()).isEqualTo("v2");
            assertThat(rotating.getReadableMasterKeyIds()).containsExactly("v1", "v2");
        }
    }

    // ── round trips ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Round trips")
    class RoundTrips {

        @Test
        @DisplayName("A data key survives wrap and unwrap unchanged")
        void dataKeyRoundTrip() {
            byte[] dataKey = service.generateDataKey();
            byte[] copy = dataKey.clone();

            String wrapped = service.wrapDataKey(dataKey, TENANT_A, KEY_VERSION, MASTER_V1);
            byte[] unwrapped = service.unwrapDataKey(wrapped, TENANT_A, KEY_VERSION, MASTER_V1);

            assertThat(unwrapped).isEqualTo(copy);
            assertThat(unwrapped).hasSize(32);
        }

        @Test
        @DisplayName("A secret value survives encrypt and decrypt unchanged")
        void secretRoundTrip() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);

            String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);

            assertThat(ciphertext).isNotEqualTo(SECRET_VALUE).isBase64();
            assertThat(service.decryptSecret(ciphertext, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION)).isEqualTo(SECRET_VALUE);
        }

        @Test
        @DisplayName("Non-ASCII and very long values round-trip")
        void unicodeAndLongValuesRoundTrip() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String value = "पासवर्ड-" + "x".repeat(1000);

            String ciphertext = service.encryptSecret(value, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "password", KEY_VERSION);

            assertThat(service.decryptSecret(ciphertext, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "password", KEY_VERSION)).isEqualTo(value);
        }
    }

    // ── nonce discipline ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Nonce discipline")
    class NonceDiscipline {

        /**
         * The one guarantee a library such as Tink would have provided for free, and the one
         * failure that breaks AES-GCM catastrophically rather than gracefully: reusing a nonce
         * under the same key leaks the XOR of the two plaintexts and the authentication subkey.
         */
        @Test
        @DisplayName("Encrypting the same value 500 times yields 500 distinct ciphertexts and nonces")
        void everyEncryptionDrawsAFreshNonce() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            Set<String> ciphertexts = new HashSet<>();
            Set<String> nonces = new HashSet<>();

            for (int i = 0; i < 500; i++) {
                String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                        MessagingChannel.EMAIL, "apiKey", KEY_VERSION);
                ciphertexts.add(ciphertext);
                byte[] decoded = Base64.getDecoder().decode(ciphertext);
                nonces.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(decoded, 0, 12)));
            }

            assertThat(nonces).as("12-byte nonces must never repeat").hasSize(500);
            assertThat(ciphertexts).hasSize(500);
        }

        @Test
        @DisplayName("Wrapping the same data key twice yields different wrapped keys")
        void everyWrapDrawsAFreshNonce() {
            byte[] dataKey = service.generateDataKey();

            String first = service.wrapDataKey(dataKey, TENANT_A, KEY_VERSION, MASTER_V1);
            String second = service.wrapDataKey(dataKey, TENANT_A, KEY_VERSION, MASTER_V1);

            assertThat(first).isNotEqualTo(second);
            assertThat(service.unwrapDataKey(first, TENANT_A, KEY_VERSION, MASTER_V1))
                    .isEqualTo(service.unwrapDataKey(second, TENANT_A, KEY_VERSION, MASTER_V1));
        }

        @Test
        @DisplayName("Generated data keys are distinct")
        void generatedDataKeysAreDistinct() {
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                keys.add(Base64.getEncoder().encodeToString(service.generateDataKey()));
            }
            assertThat(keys).hasSize(100);
        }
    }

    // ── AAD binding (S-7) ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("AAD binds a ciphertext to its row")
    class AadBinding {

        private byte[] dataKey;
        private String ciphertext;

        @BeforeEach
        void encryptForTenantA() {
            dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);
        }

        @Test
        @DisplayName("A secret moved to another tenant fails to decrypt")
        void movedToAnotherTenantFails() {
            assertThatThrownBy(() -> service.decryptSecret(ciphertext, dataKey, TENANT_B,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class)
                    .hasMessageContaining(AEADBadTagException.class.getSimpleName());
        }

        @Test
        @DisplayName("A secret moved to another channel fails to decrypt")
        void movedToAnotherChannelFails() {
            assertThatThrownBy(() -> service.decryptSecret(ciphertext, dataKey, TENANT_A,
                    MessagingChannel.SMS, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A secret moved to another secret name fails to decrypt")
        void movedToAnotherSecretNameFails() {
            assertThatThrownBy(() -> service.decryptSecret(ciphertext, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "password", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A secret read under another key version fails to decrypt")
        void movedToAnotherKeyVersionFails() {
            assertThatThrownBy(() -> service.decryptSecret(ciphertext, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION + 1))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A wrapped data key moved to another tenant or version fails to unwrap")
        void wrappedKeyIsBoundToItsRow() {
            byte[] raw = service.generateDataKey();
            String wrapped = service.wrapDataKey(raw, TENANT_A, KEY_VERSION, MASTER_V1);

            assertThatThrownBy(() -> service.unwrapDataKey(wrapped, TENANT_B, KEY_VERSION, MASTER_V1))
                    .isInstanceOf(SecretCryptoException.class);
            assertThatThrownBy(() -> service.unwrapDataKey(wrapped, TENANT_A, KEY_VERSION + 1, MASTER_V1))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A wrapped data key attributed to the wrong master key id fails to unwrap")
        void wrappedKeyIsBoundToItsMasterKeyId() {
            // Two ids, the SAME key bytes behind both: only the AAD distinguishes them, which is
            // what stops a row's master_key_id being edited in the database to point elsewhere.
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("v1", keyV1);
            keys.put("v2", keyV1);
            SecretCryptoService twoIds = serviceWith("v1", keys);

            byte[] raw = twoIds.generateDataKey();
            String wrapped = twoIds.wrapDataKey(raw, TENANT_A, KEY_VERSION, "v1");

            assertThatThrownBy(() -> twoIds.unwrapDataKey(wrapped, TENANT_A, KEY_VERSION, "v2"))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A plain AES-GCM decrypt that omits the AAD fails")
        void decryptWithoutAadFails() throws Exception {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(decoded, 0, 12);
            byte[] body = Arrays.copyOfRange(decoded, 12, decoded.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, iv));
            // No updateAAD — exactly what an attacker holding the DEK but not the row context has.
            assertThatThrownBy(() -> cipher.doFinal(body)).isInstanceOf(AEADBadTagException.class);
        }

        @Test
        @DisplayName("A plain AES-GCM decrypt with the correct AAD succeeds, proving the format")
        void decryptWithCorrectAadSucceeds() throws Exception {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(decoded, 0, 12);
            byte[] body = Arrays.copyOfRange(decoded, 12, decoded.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD((TENANT_A + "|EMAIL|apiKey|" + KEY_VERSION).getBytes(UTF_8));

            assertThat(new String(cipher.doFinal(body), UTF_8)).isEqualTo(SECRET_VALUE);
        }
    }

    // ── failure handling: never a fallback ──────────────────────────────────────

    @Nested
    @DisplayName("Failures never fall back to plaintext")
    class FailuresNeverFallBack {

        @Test
        @DisplayName("The wrong master key fails to unwrap")
        void wrongMasterKeyFails() {
            byte[] dataKey = service.generateDataKey();
            String wrapped = service.wrapDataKey(dataKey, TENANT_A, KEY_VERSION, MASTER_V1);

            // Same id, different key bytes — a staging dump restored into production.
            SecretCryptoService otherEnvironment = serviceWith(MASTER_V1, Map.of(MASTER_V1, randomKey()));

            assertThatThrownBy(() -> otherEnvironment.unwrapDataKey(wrapped, TENANT_A, KEY_VERSION, MASTER_V1))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("An unconfigured master key id is reported by id, not silently skipped")
        void unknownMasterKeyIdFails() {
            byte[] dataKey = service.generateDataKey();
            String wrapped = service.wrapDataKey(dataKey, TENANT_A, KEY_VERSION, MASTER_V1);

            assertThatThrownBy(() -> service.unwrapDataKey(wrapped, TENANT_A, KEY_VERSION, "v0"))
                    .isInstanceOf(SecretCryptoException.class)
                    .hasMessageContaining("'v0' is not configured");
        }

        @Test
        @DisplayName("The wrong data key fails to decrypt a secret")
        void wrongDataKeyFails() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);
            byte[] otherTenantsKey = dataKeyFor(service, TENANT_B, KEY_VERSION);

            assertThatThrownBy(() -> service.decryptSecret(ciphertext, otherTenantsKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A tampered ciphertext fails rather than returning altered plaintext")
        void tamperedCiphertextFails() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);

            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            decoded[decoded.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(decoded);

            assertThatThrownBy(() -> service.decryptSecret(tampered, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A truncated ciphertext fails")
        void truncatedCiphertextFails() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            String truncated = Base64.getEncoder().encodeToString(Arrays.copyOfRange(decoded, 0, decoded.length - 4));

            assertThatThrownBy(() -> service.decryptSecret(truncated, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A value shorter than an AES-GCM payload is rejected, not returned as-is")
        void tooShortValueIsRejectedNotReturned() {
            // PiiEncryptionService.safeDecrypt returns a value this short unchanged, treating it as
            // a legacy plaintext row. For a credential that behaviour would be a security hole,
            // so the same input must fail here.
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String legacyLookingValue = Base64.getEncoder().encodeToString("plain".getBytes(UTF_8));

            assertThatThrownBy(() -> service.decryptSecret(legacyLookingValue, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class)
                    .hasMessageContaining("28-byte minimum");
        }

        @Test
        @DisplayName("A non-base64 stored value is rejected")
        void nonBase64CiphertextFails() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);

            assertThatThrownBy(() -> service.decryptSecret("this is not base64 !!", dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class)
                    .hasMessageContaining("not valid base64");
        }

        @Test
        @DisplayName("An absent stored value is rejected")
        void absentCiphertextFails() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);

            assertThatThrownBy(() -> service.decryptSecret(null, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class)
                    .hasMessageContaining("absent");
            assertThatThrownBy(() -> service.decryptSecret("   ", dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class);
        }

        @Test
        @DisplayName("A data key of the wrong length is rejected before it is used to wrap")
        void wrongLengthDataKeyRejected() {
            assertThatThrownBy(() -> service.wrapDataKey(new byte[16], TENANT_A, KEY_VERSION, MASTER_V1))
                    .isInstanceOf(SecretCryptoException.class)
                    .hasMessageContaining("exactly 32 bytes");
            assertThatThrownBy(() -> service.wrapDataKey(null, TENANT_A, KEY_VERSION, MASTER_V1))
                    .isInstanceOf(SecretCryptoException.class);
        }
    }

    // ── nothing secret ever reaches a message or a log (S-4) ────────────────────

    @Nested
    @DisplayName("No secret or key material in any rendering")
    class NoLeakage {

        @Test
        @DisplayName("toString() names key ids only")
        void toStringHasNoKeyMaterial() {
            assertThat(service.toString())
                    .isEqualTo("SecretCryptoService(activeMasterKeyId=v1, readableMasterKeyIds=[v1])")
                    .doesNotContain(keyV1);
        }

        @Test
        @DisplayName("A failure message names the row, never the value or the key")
        void failureMessagesNameTheRowOnly() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);

            assertThatThrownBy(() -> service.decryptSecret(ciphertext, dataKey, TENANT_B,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .isInstanceOf(SecretCryptoException.class)
                    .satisfies(thrown -> {
                        String rendered = renderWithCauses(thrown);
                        assertThat(rendered)
                                .contains("tenantId=8", "channel=EMAIL", "secretName=apiKey", "keyVersion=1")
                                .doesNotContain(SECRET_VALUE)
                                .doesNotContain(ciphertext)
                                .doesNotContain(keyV1)
                                .doesNotContain(Base64.getEncoder().encodeToString(dataKey));
                    });
        }

        @Test
        @DisplayName("No cause is attached, so no stack trace can carry ciphertext into a log")
        void noCauseIsAttached() {
            byte[] dataKey = dataKeyFor(service, TENANT_A, KEY_VERSION);
            String ciphertext = service.encryptSecret(SECRET_VALUE, dataKey, TENANT_A,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION);

            assertThatThrownBy(() -> service.decryptSecret(ciphertext, dataKey, TENANT_B,
                    MessagingChannel.EMAIL, "apiKey", KEY_VERSION))
                    .hasNoCause();
        }

        private String renderWithCauses(Throwable thrown) {
            StringBuilder rendered = new StringBuilder();
            for (Throwable t = thrown; t != null; t = t.getCause()) {
                rendered.append(t).append('\n');
            }
            return rendered.toString();
        }
    }

    // ── zeroise ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Zeroise")
    class Zeroise {

        @Test
        @DisplayName("zeroise() overwrites the array in place")
        void overwritesInPlace() {
            byte[] dataKey = service.generateDataKey();
            assertThat(dataKey).isNotEqualTo(new byte[32]);

            SecretCryptoService.zeroise(dataKey);

            assertThat(dataKey).containsOnly((byte) 0);
        }

        @Test
        @DisplayName("zeroise(null) is safe, so it can be called from a finally block")
        void nullIsSafe() {
            assertThatCode(() -> SecretCryptoService.zeroise(null)).doesNotThrowAnyException();
        }
    }
}
