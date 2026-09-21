package org.arghyam.jalsoochak.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.message.config.MessagingSecretProperties;
import org.arghyam.jalsoochak.message.dto.TenantProviderSecretRow;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.dto.TenantSecretKeyRow;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.exception.SecretCryptoException;
import org.arghyam.jalsoochak.message.repository.TenantProviderSecretRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PER-TENANT-PROVIDERS: unit tests for {@link TenantSecretResolver}.
 *
 * <p>The repository is mocked, but {@link SecretCryptoService} is real: the point of most of these
 * is what happens when a ciphertext does not authenticate, and a mocked crypto service could not
 * show it. Rows are therefore encrypted for real in the fixtures.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantSecretResolver Tests")
class TenantSecretResolverTest {

    private static final int TENANT_A_ID = 7;
    private static final int TENANT_B_ID = 8;
    private static final TenantRef TENANT_A = new TenantRef(TENANT_A_ID, "MP");
    private static final int KEY_VERSION = 1;
    private static final String MASTER_V1 = "v1";
    private static final String API_KEY = "SG.aVeryRealLookingSendGridApiKey.0123456789abcdef";

    @Mock
    private TenantProviderSecretRepository secretRepository;

    private SecretCryptoService cryptoService;
    private TenantSecretResolver resolver;
    private byte[] tenantADataKey;
    private String tenantAWrappedKey;

    @BeforeEach
    void setUp() {
        byte[] masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        MessagingSecretProperties props = new MessagingSecretProperties();
        props.setActiveMasterKeyId(MASTER_V1);
        props.setMasterKeys(new LinkedHashMap<>(Map.of(MASTER_V1, Base64.getEncoder().encodeToString(masterKey))));

        cryptoService = new SecretCryptoService(props);
        resolver = new TenantSecretResolver(secretRepository, cryptoService);

        tenantADataKey = cryptoService.generateDataKey();
        tenantAWrappedKey = cryptoService.wrapDataKey(tenantADataKey, TENANT_A_ID, KEY_VERSION, MASTER_V1);
    }

    private TenantSecretKeyRow keyRow(int tenantId, String wrapped) {
        return new TenantSecretKeyRow(tenantId, KEY_VERSION, wrapped, MASTER_V1, "ACTIVE");
    }

    private TenantProviderSecretRow secretRow(int tenantId, MessagingChannel channel, String name,
            String value) {
        String ciphertext = cryptoService.encryptSecret(value, tenantADataKey, tenantId, channel, name,
                KEY_VERSION);
        return new TenantProviderSecretRow(tenantId, channel, name, ciphertext, KEY_VERSION);
    }

    // ── resolve ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a stored secret round-trips to its plaintext")
    void storedSecretRoundTrips() {
        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.of(secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", API_KEY)));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION))
                .thenReturn(Optional.of(keyRow(TENANT_A_ID, tenantAWrappedKey)));

        assertThat(resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey")).contains(API_KEY);
    }

    @Test
    @DisplayName("a secret that was never written resolves to empty, not an error")
    void missingSecretResolvesEmpty() {
        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey")).isEmpty();
    }

    @Test
    @DisplayName("an event with no tenant id reads nothing")
    void noTenantIdReadsNothing() {
        assertThat(resolver.resolve(TenantRef.NONE, MessagingChannel.EMAIL, "apiKey")).isEmpty();
        assertThat(resolver.resolve(new TenantRef(null, "MP"), MessagingChannel.EMAIL, "apiKey")).isEmpty();
        assertThat(resolver.resolve(null, MessagingChannel.EMAIL, "apiKey")).isEmpty();

        verifyNoInteractions(secretRepository);
    }

    @Test
    @DisplayName("the location is derived from the tenant, never supplied by a caller")
    void locationIsDerivedFromTheTenant() {
        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.empty());

        resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey");

        // The only tenant id that reaches the repository is the one on the TenantRef: there is no
        // parameter through which a settings value could name another tenant's row (S-1, O2-8).
        verify(secretRepository).findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey");
        verify(secretRepository, never()).findSecret(eq(TENANT_B_ID), any(), anyString());
    }

    @Test
    @DisplayName("a secret name outside the channel's closed set is a coding error")
    void unknownSecretNameIsRejected() {
        // Not answered with empty: "missing credential" and "the code asked for a name that does not
        // exist" must not look the same, or a typo would read as a tenant misconfiguration forever.
        assertThatThrownBy(() -> resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "authKey"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no secret named");
        assertThatThrownBy(() -> resolver.resolve(TENANT_A, MessagingChannel.EMAIL,
                "SPRING_DATASOURCE_PASSWORD"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(secretRepository);
    }

    // ── AAD binding and failure ─────────────────────────────────────────────────

    @Test
    @DisplayName("a ciphertext copied from another tenant fails rather than decrypting")
    void ciphertextMovedBetweenTenantsFails() {
        // The row is tenant B's ciphertext presented as tenant A's — a copied database row, or a
        // SQL injection that swapped a tenant_id. The AAD is what makes this fail (S-7).
        TenantProviderSecretRow foreign = secretRow(TENANT_B_ID, MessagingChannel.EMAIL, "apiKey", API_KEY);
        TenantProviderSecretRow relabelled = new TenantProviderSecretRow(
                TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", foreign.ciphertext(), KEY_VERSION);

        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.of(relabelled));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION))
                .thenReturn(Optional.of(keyRow(TENANT_A_ID, tenantAWrappedKey)));

        assertThatThrownBy(() -> resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey"))
                .isInstanceOf(SecretCryptoException.class);
    }

    @Test
    @DisplayName("a secret naming a key version that is not stored fails loudly")
    void missingKeyVersionFails() {
        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.of(secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", API_KEY)));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey"))
                .isInstanceOf(SecretCryptoException.class)
                .hasMessageContaining("key version that is not stored");
    }

    @Test
    @DisplayName("a tampered ciphertext fails; there is never a plaintext fallback")
    void tamperedCiphertextNeverFallsBack() {
        TenantProviderSecretRow row = secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", API_KEY);
        String tampered = row.ciphertext().substring(0, row.ciphertext().length() - 4) + "AAAA";

        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.of(new TenantProviderSecretRow(
                        TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", tampered, KEY_VERSION)));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION))
                .thenReturn(Optional.of(keyRow(TENANT_A_ID, tenantAWrappedKey)));

        assertThatThrownBy(() -> resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey"))
                .isInstanceOf(SecretCryptoException.class);
    }

    @Test
    @DisplayName("a failure names the row and never the value")
    void failureNeverCarriesTheValue() {
        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.of(secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", API_KEY)));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey"))
                .hasMessageNotContaining(API_KEY)
                .hasMessageContaining("secretName=apiKey")
                .hasMessageContaining("tenantId=" + TENANT_A_ID);
    }

    @Test
    @DisplayName("a retired key version still decrypts an unrotated secret")
    void retiredKeyVersionStillDecrypts() {
        // Retired means "no longer used for new writes", not "unreadable": a send landing mid
        // rotation must not fail for a reason no operator caused.
        when(secretRepository.findSecret(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey"))
                .thenReturn(Optional.of(secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", API_KEY)));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION)).thenReturn(Optional.of(
                new TenantSecretKeyRow(TENANT_A_ID, KEY_VERSION, tenantAWrappedKey, MASTER_V1, "RETIRED")));

        assertThat(resolver.resolve(TENANT_A, MessagingChannel.EMAIL, "apiKey")).contains(API_KEY);
        // Looked up by the version the row names, never by "whichever is active now".
        verify(secretRepository, never()).findActiveKey(anyInt());
    }

    // ── resolveAll ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveAll returns every required credential in one pass")
    void resolveAllReturnsEveryRequiredCredential() {
        when(secretRepository.findByTenantAndChannel(TENANT_A_ID, MessagingChannel.SMS)).thenReturn(List.of(
                secretRow(TENANT_A_ID, MessagingChannel.SMS, "authKey", "the-auth-key"),
                secretRow(TENANT_A_ID, MessagingChannel.SMS, "authToken", "the-auth-token")));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION))
                .thenReturn(Optional.of(keyRow(TENANT_A_ID, tenantAWrappedKey)));

        Optional<TenantSecrets> secrets = resolver.resolveAll(
                TENANT_A, MessagingChannel.SMS, Set.of("authKey", "authToken"));

        assertThat(secrets).isPresent();
        assertThat(secrets.get().get("authKey")).isEqualTo("the-auth-key");
        assertThat(secrets.get().get("authToken")).isEqualTo("the-auth-token");
        // One query for the whole channel, so a rotation cannot land between two credentials of the
        // same provider.
        verify(secretRepository).findByTenantAndChannel(TENANT_A_ID, MessagingChannel.SMS);
    }

    @Test
    @DisplayName("resolveAll is all-or-nothing when one required credential is absent")
    void resolveAllIsAllOrNothing() {
        when(secretRepository.findByTenantAndChannel(TENANT_A_ID, MessagingChannel.SMS)).thenReturn(List.of(
                secretRow(TENANT_A_ID, MessagingChannel.SMS, "authKey", "the-auth-key")));

        assertThat(resolver.resolveAll(TENANT_A, MessagingChannel.SMS, Set.of("authKey", "authToken")))
                .isEmpty();

        // No key was unwrapped: completeness is decided before anything is decrypted, so the
        // present credential is never brought into memory only to be discarded.
        verify(secretRepository, never()).findKey(anyInt(), anyInt());
    }

    @Test
    @DisplayName("resolveAll ignores stored credentials the provider does not require")
    void resolveAllIgnoresUnrequiredCredentials() {
        // A tenant that used SMTP and switched to SendGrid still has a stored password. It must not
        // be decrypted, let alone handed to the SendGrid factory.
        when(secretRepository.findByTenantAndChannel(TENANT_A_ID, MessagingChannel.EMAIL)).thenReturn(List.of(
                secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "apiKey", API_KEY),
                secretRow(TENANT_A_ID, MessagingChannel.EMAIL, "password", "the-old-smtp-password")));
        when(secretRepository.findKey(TENANT_A_ID, KEY_VERSION))
                .thenReturn(Optional.of(keyRow(TENANT_A_ID, tenantAWrappedKey)));

        Optional<TenantSecrets> secrets = resolver.resolveAll(
                TENANT_A, MessagingChannel.EMAIL, Set.of("apiKey"));

        assertThat(secrets).isPresent();
        assertThat(secrets.get().names()).containsExactly("apiKey");
        assertThat(secrets.get().get("password")).isNull();
    }

    @Test
    @DisplayName("resolveAll with no tenant or no required names reads nothing")
    void resolveAllShortCircuits() {
        assertThat(resolver.resolveAll(TenantRef.NONE, MessagingChannel.EMAIL, Set.of("apiKey"))).isEmpty();
        assertThat(resolver.resolveAll(TENANT_A, MessagingChannel.EMAIL, Set.of())).isEmpty();

        verifyNoInteractions(secretRepository);
    }

    @Test
    @DisplayName("TenantSecrets never prints a value")
    void tenantSecretsNeverPrintsAValue() {
        TenantSecrets secrets = TenantSecrets.of(MessagingChannel.EMAIL, Map.of("apiKey", API_KEY));

        assertThat(secrets.toString()).doesNotContain(API_KEY).contains("apiKey").contains("EMAIL");
    }
}
