package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.arghyam.jalsoochak.tenant.config.properties.MessagingSecretProperties;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantProviderSecretDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantSecretKeyDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSecretsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRewrapResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRotationResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.enums.SecretStatus;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.event.TenantConfigUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.exception.SecretStoreUnavailableException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantProviderSecretRepository;
import org.arghyam.jalsoochak.tenant.service.SecretCryptoService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * MESSAGING-PROVIDER-SECRETS: unit tests for {@link TenantMessagingSecretServiceImpl}.
 *
 * <p>The repositories are mocked; {@link SecretCryptoService} is <b>real</b>. Mocking the
 * crypto would leave the parts most worth testing unproven — that a rotation decrypts under
 * the row's own key version and re-encrypts under the new one, and that a secret's ciphertext
 * is bound to the row it is written to. A stub returning "ciphertext" would pass either way.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantMessagingSecretServiceImpl Tests")
class TenantMessagingSecretServiceImplTest {

    private static final Integer TENANT_MP = 101;
    private static final Integer ADMIN_USER = 900;
    private static final String ACTOR_UUID = "actor-uuid";
    private static final String MASTER_V1 = "v1";
    private static final String AUTH_KEY_VALUE = "smscountry-auth-key";
    private static final String AUTH_TOKEN_VALUE = "smscountry-auth-token";

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @Mock
    private TenantProviderSecretRepository secretRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MockedStatic<SecurityUtils> mockedSecurityUtils;
    private String keyV1;
    private SecretCryptoService crypto;
    private TenantMessagingSecretServiceImpl service;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecretCryptoService cryptoWith(String activeId, Map<String, String> keys) {
        MessagingSecretProperties props = new MessagingSecretProperties();
        props.setActiveMasterKeyId(activeId);
        props.setMasterKeys(keys);
        return new SecretCryptoService(props);
    }

    @BeforeEach
    void setUp() {
        mockedSecurityUtils = mockStatic(SecurityUtils.class);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUserUuid).thenReturn(ACTOR_UUID);
        keyV1 = randomKey();
        useCrypto(cryptoWith(MASTER_V1, new LinkedHashMap<>(Map.of(MASTER_V1, keyV1))));
    }

    @AfterEach
    void tearDown() {
        mockedSecurityUtils.close();
    }

    private void useCrypto(SecretCryptoService replacement) {
        this.crypto = replacement;
        this.service = new TenantMessagingSecretServiceImpl(
                tenantCommonRepository, secretRepository, replacement, eventPublisher);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private void givenOnboardedTenant() {
        when(tenantCommonRepository.findById(TENANT_MP)).thenReturn(Optional.of(TenantResponseDTO.builder()
                .id(TENANT_MP).stateCode("MP").name("Madhya Pradesh")
                .status(TenantStatusEnum.ACTIVE.name()).build()));
    }

    private void givenCurrentUser() {
        when(tenantCommonRepository.findUserIdByUuid(ACTOR_UUID)).thenReturn(Optional.of(ADMIN_USER));
    }

    /** A real wrapped key for the tenant, as the database would hold it. */
    private TenantSecretKeyDTO realKeyRow(int keyVersion, String masterKeyId, SecretCryptoService with) {
        byte[] dataKey = with.generateDataKey();
        try {
            return TenantSecretKeyDTO.builder()
                    .id(500 + keyVersion)
                    .tenantId(TENANT_MP)
                    .keyVersion(keyVersion)
                    .wrappedKey(with.wrapDataKey(dataKey, TENANT_MP, keyVersion, masterKeyId))
                    .masterKeyId(masterKeyId)
                    .status(TenantProviderSecretRepository.KEY_STATUS_ACTIVE)
                    .build();
        } finally {
            SecretCryptoService.zeroise(dataKey);
        }
    }

    /** A real ciphertext row for a value, encrypted under {@code key}. */
    private TenantProviderSecretDTO realSecretRow(int id, MessagingChannel channel, String name,
            String value, TenantSecretKeyDTO key) {
        byte[] dataKey = crypto.unwrapDataKey(
                key.getWrappedKey(), TENANT_MP, key.getKeyVersion(), key.getMasterKeyId());
        try {
            return TenantProviderSecretDTO.builder()
                    .id(id)
                    .tenantId(TENANT_MP)
                    .channel(channel)
                    .secretName(name)
                    .ciphertext(crypto.encryptSecret(value, dataKey, TENANT_MP, channel, name, key.getKeyVersion()))
                    .keyVersion(key.getKeyVersion())
                    .build();
        } finally {
            SecretCryptoService.zeroise(dataKey);
        }
    }

    private static SetMessagingProviderSecretsRequestDTO request(Map<String, String> secrets) {
        SetMessagingProviderSecretsRequestDTO dto = new SetMessagingProviderSecretsRequestDTO();
        dto.setSecrets(secrets);
        return dto;
    }

    /** Captures what was handed to the repository and decrypts it, as message-service would. */
    private String decryptCaptured(String ciphertext, TenantSecretKeyDTO key,
            MessagingChannel channel, String name) {
        byte[] dataKey = crypto.unwrapDataKey(
                key.getWrappedKey(), TENANT_MP, key.getKeyVersion(), key.getMasterKeyId());
        try {
            return crypto.decryptSecret(ciphertext, dataKey, TENANT_MP, channel, name, key.getKeyVersion());
        } finally {
            SecretCryptoService.zeroise(dataKey);
        }
    }

    // ── setSecrets ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setSecrets")
    class SetSecrets {

        @Test
        @DisplayName("Stores each value as ciphertext that decrypts back to the original")
        void storesRecoverableCiphertext() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO key = realKeyRow(1, MASTER_V1, crypto);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(key));
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS))
                    .thenReturn(List.of());

            Map<String, String> secrets = new LinkedHashMap<>();
            secrets.put("authKey", AUTH_KEY_VALUE);
            secrets.put("authToken", AUTH_TOKEN_VALUE);
            service.setSecrets(TENANT_MP, MessagingChannel.SMS, request(secrets));

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
            verify(secretRepository, times(2)).upsertSecret(eq(TENANT_MP), eq(MessagingChannel.SMS),
                    name.capture(), ciphertext.capture(), eq(1), eq(ADMIN_USER));

            assertThat(name.getAllValues()).containsExactly("authKey", "authToken");
            assertThat(ciphertext.getAllValues()).doesNotContain(AUTH_KEY_VALUE, AUTH_TOKEN_VALUE);
            assertThat(decryptCaptured(ciphertext.getAllValues().get(0), key, MessagingChannel.SMS, "authKey"))
                    .isEqualTo(AUTH_KEY_VALUE);
            assertThat(decryptCaptured(ciphertext.getAllValues().get(1), key, MessagingChannel.SMS, "authToken"))
                    .isEqualTo(AUTH_TOKEN_VALUE);
        }

        @Test
        @DisplayName("Creates the tenant's first data key on first write")
        void createsDataKeyOnDemand() {
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.empty());
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(0);
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(1), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> TenantSecretKeyDTO.builder()
                            .id(1).tenantId(TENANT_MP).keyVersion(1)
                            .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1)
                            .status(TenantProviderSecretRepository.KEY_STATUS_ACTIVE).build());
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.EMAIL))
                    .thenReturn(List.of());

            service.setSecrets(TENANT_MP, MessagingChannel.EMAIL, request(Map.of("apiKey", "SG.key")));

            ArgumentCaptor<String> wrapped = ArgumentCaptor.forClass(String.class);
            verify(secretRepository).insertActiveKey(eq(TENANT_MP), eq(1), wrapped.capture(),
                    eq(MASTER_V1), eq(ADMIN_USER));
            // The wrapped key really is unwrappable for this tenant and version.
            assertThat(crypto.unwrapDataKey(wrapped.getValue(), TENANT_MP, 1, MASTER_V1)).hasSize(32);
        }

        @Test
        @DisplayName("Takes the tenant's key lock before deciding whether to create one")
        void locksBeforeDecidingToCreateADataKey() {
            // The read and the insert are one decision. Without the lock, two admins setting EMAIL
            // and SMS secrets in parallel on a tenant with no key both see none, both compute
            // version 1, and the loser fails uq_tenant_secret_key_active as a 500. The lock has to
            // precede the read: taking it after would leave the read it protects unprotected.
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.empty());
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(0);
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(1), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> TenantSecretKeyDTO.builder()
                            .id(1).tenantId(TENANT_MP).keyVersion(1)
                            .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1)
                            .status(TenantProviderSecretRepository.KEY_STATUS_ACTIVE).build());
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.EMAIL))
                    .thenReturn(List.of());

            service.setSecrets(TENANT_MP, MessagingChannel.EMAIL, request(Map.of("apiKey", "SG.key")));

            InOrder order = inOrder(secretRepository);
            order.verify(secretRepository).lockKeys(TENANT_MP);
            order.verify(secretRepository).findActiveKey(TENANT_MP);
            order.verify(secretRepository).insertActiveKey(eq(TENANT_MP), eq(1), anyString(),
                    eq(MASTER_V1), eq(ADMIN_USER));
        }

        @Test
        @DisplayName("Reuses the existing data key rather than issuing a second one")
        void reusesExistingDataKey() {
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findActiveKey(TENANT_MP))
                    .thenReturn(Optional.of(realKeyRow(3, MASTER_V1, crypto)));
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            service.setSecrets(TENANT_MP, MessagingChannel.SMS, request(Map.of("authKey", AUTH_KEY_VALUE)));

            verify(secretRepository, never()).insertActiveKey(anyInt(), anyInt(), anyString(), anyString(), anyInt());
            verify(secretRepository).upsertSecret(eq(TENANT_MP), eq(MessagingChannel.SMS), eq("authKey"),
                    anyString(), eq(3), eq(ADMIN_USER));
        }

        @Test
        @DisplayName("Values are trimmed, so a pasted trailing newline cannot break authentication")
        void trimsValues() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO key = realKeyRow(1, MASTER_V1, crypto);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(key));
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            service.setSecrets(TENANT_MP, MessagingChannel.SMS,
                    request(Map.of(" authKey ", "  " + AUTH_KEY_VALUE + "\n")));

            ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
            verify(secretRepository).upsertSecret(eq(TENANT_MP), eq(MessagingChannel.SMS), eq("authKey"),
                    ciphertext.capture(), eq(1), eq(ADMIN_USER));
            assertThat(decryptCaptured(ciphertext.getValue(), key, MessagingChannel.SMS, "authKey"))
                    .isEqualTo(AUTH_KEY_VALUE);
        }

        @Test
        @DisplayName("Raises TENANT_CONFIG_UPDATED for the channel's settings key, so message-service evicts")
        void publishesConfigUpdatedEvent() {
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findActiveKey(TENANT_MP))
                    .thenReturn(Optional.of(realKeyRow(1, MASTER_V1, crypto)));
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            service.setSecrets(TENANT_MP, MessagingChannel.SMS, request(Map.of("authKey", AUTH_KEY_VALUE)));

            ArgumentCaptor<TenantConfigUpdatedEvent> event =
                    ArgumentCaptor.forClass(TenantConfigUpdatedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().getTenantId()).isEqualTo(TENANT_MP);
            assertThat(event.getValue().getStateCode()).isEqualTo("MP");
            assertThat(event.getValue().getConfigKeys()).containsExactly("SMS_PROVIDER_SETTINGS");
        }

        @Test
        @DisplayName("Reports SET for what was written and MISSING for the rest")
        void reportsStatusWithoutValues() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO key = realKeyRow(1, MASTER_V1, crypto);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(key));
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(
                    List.of(realSecretRow(1, MessagingChannel.SMS, "authKey", AUTH_KEY_VALUE, key)));

            MessagingProviderSecretStatusResponseDTO response = service.setSecrets(
                    TENANT_MP, MessagingChannel.SMS, request(Map.of("authKey", AUTH_KEY_VALUE)));

            assertThat(response.getTenantId()).isEqualTo(TENANT_MP);
            assertThat(response.getChannel()).isEqualTo(MessagingChannel.SMS);
            assertThat(response.getSecrets())
                    .containsEntry("authKey", SecretStatus.SET)
                    .containsEntry("authToken", SecretStatus.MISSING);
            assertThat(response.getKeyVersion()).isEqualTo(1);
            assertThat(response.toString()).doesNotContain(AUTH_KEY_VALUE);
        }

        @Test
        @DisplayName("A secret name belonging to another channel is rejected before anything is written")
        void rejectsNameFromAnotherChannel() {
            givenOnboardedTenant();
            // No user stub: the name/value check must reject before any user lookup.

            assertThatThrownBy(() -> service.setSecrets(TENANT_MP, MessagingChannel.SMS,
                    request(Map.of("apiKey", "SG.key"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown secret name 'apiKey' for channel SMS")
                    .hasMessageContaining("authKey");

            verify(secretRepository, never()).upsertSecret(anyInt(), any(), anyString(), anyString(),
                    anyInt(), anyInt());
        }

        @Test
        @DisplayName("A platform secret name is rejected — the store is not addressable by the caller")
        void rejectsArbitraryName() {
            givenOnboardedTenant();
            // No user stub: the name/value check must reject before any user lookup.

            assertThatThrownBy(() -> service.setSecrets(TENANT_MP, MessagingChannel.EMAIL,
                    request(Map.of("SPRING_DATASOURCE_PASSWORD", "hunter2"))))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("A blank value is rejected without echoing it")
        void rejectsBlankValue() {
            givenOnboardedTenant();
            // No user stub: the name/value check must reject before any user lookup.

            assertThatThrownBy(() -> service.setSecrets(TENANT_MP, MessagingChannel.SMS,
                    request(Map.of("authKey", "   "))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("Answers 503 when the deployment has no master key, before touching the database")
        void requiresAMasterKey() {
            useCrypto(cryptoWith("", Map.of()));

            assertThatThrownBy(() -> service.setSecrets(TENANT_MP, MessagingChannel.SMS,
                    request(Map.of("authKey", AUTH_KEY_VALUE))))
                    .isInstanceOf(SecretStoreUnavailableException.class)
                    .hasMessageContaining("no master key is configured");

            verify(tenantCommonRepository, never()).findById(anyInt());
        }

        @Test
        @DisplayName("The system tenant holds platform defaults and has no provider account")
        void rejectsSystemTenant() {
            assertThatThrownBy(() -> service.setSecrets(0, MessagingChannel.SMS,
                    request(Map.of("authKey", AUTH_KEY_VALUE))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("system tenant");

            verify(tenantCommonRepository, never()).findById(anyInt());
        }

        @Test
        @DisplayName("An unknown tenant is not found")
        void rejectsUnknownTenant() {
            when(tenantCommonRepository.findById(TENANT_MP)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setSecrets(TENANT_MP, MessagingChannel.SMS,
                    request(Map.of("authKey", AUTH_KEY_VALUE))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        @DisplayName("A registered-but-never-onboarded tenant is treated as not found")
        void rejectsNotOnboardedTenant() {
            when(tenantCommonRepository.findById(TENANT_MP)).thenReturn(Optional.of(TenantResponseDTO.builder()
                    .id(TENANT_MP).stateCode("MP").status(TenantStatusEnum.REGISTERED.name()).build()));

            assertThatThrownBy(() -> service.setSecrets(TENANT_MP, MessagingChannel.SMS,
                    request(Map.of("authKey", AUTH_KEY_VALUE))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not onboarded");
        }
    }

    // ── deleteSecrets ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteSecrets")
    class DeleteSecrets {

        @Test
        @DisplayName("Soft-deletes the channel's secrets and publishes the eviction event")
        void deletesAndEvicts() {
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findSecretNames(TENANT_MP, MessagingChannel.SMS))
                    .thenReturn(Set.of("authKey", "authToken"));
            when(secretRepository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER))
                    .thenReturn(2);
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            MessagingProviderSecretStatusResponseDTO response =
                    service.deleteSecrets(TENANT_MP, MessagingChannel.SMS);

            assertThat(response.getSecrets()).containsValues(SecretStatus.MISSING, SecretStatus.MISSING);
            assertThat(response.getKeyVersion()).isNull();
            ArgumentCaptor<TenantConfigUpdatedEvent> event =
                    ArgumentCaptor.forClass(TenantConfigUpdatedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().getConfigKeys()).containsExactly("SMS_PROVIDER_SETTINGS");
        }

        @Test
        @DisplayName("Deleting nothing publishes no event")
        void noEventWhenNothingDeleted() {
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findSecretNames(TENANT_MP, MessagingChannel.SMS)).thenReturn(Set.of());
            when(secretRepository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER))
                    .thenReturn(0);
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            service.deleteSecrets(TENANT_MP, MessagingChannel.SMS);

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Works with no master key configured — removing ciphertext needs no key")
        void worksWithoutAMasterKey() {
            // Otherwise a lost or rotated-away master key would trap a credential the tenant
            // wants gone, with no way to fall back to the system default.
            useCrypto(cryptoWith("", Map.of()));
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findSecretNames(TENANT_MP, MessagingChannel.SMS)).thenReturn(Set.of("authKey"));
            when(secretRepository.softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER))
                    .thenReturn(1);
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            service.deleteSecrets(TENANT_MP, MessagingChannel.SMS);

            verify(secretRepository).softDeleteByTenantAndChannel(TENANT_MP, MessagingChannel.SMS, ADMIN_USER);
        }
    }

    // ── getSecretStatus ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSecretStatus")
    class GetSecretStatus {

        @Test
        @DisplayName("Lists every supported name and never a value")
        void listsAllSupportedNames() {
            givenOnboardedTenant();
            TenantSecretKeyDTO key = realKeyRow(2, MASTER_V1, crypto);
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.EMAIL)).thenReturn(
                    List.of(realSecretRow(1, MessagingChannel.EMAIL, "apiKey", "SG.key", key)));

            MessagingProviderSecretStatusResponseDTO response =
                    service.getSecretStatus(TENANT_MP, MessagingChannel.EMAIL);

            assertThat(response.getSecrets()).containsOnlyKeys("apiKey", "password");
            assertThat(response.getSecrets().get("apiKey")).isEqualTo(SecretStatus.SET);
            assertThat(response.getSecrets().get("password")).isEqualTo(SecretStatus.MISSING);
            assertThat(response.getKeyVersion()).isEqualTo(2);
            assertThat(response.toString()).doesNotContain("SG.key");
        }

        @Test
        @DisplayName("A tenant with nothing stored reports every name MISSING")
        void unconfiguredTenantIsAllMissing() {
            givenOnboardedTenant();
            when(secretRepository.findByTenantAndChannel(TENANT_MP, MessagingChannel.SMS)).thenReturn(List.of());

            MessagingProviderSecretStatusResponseDTO response =
                    service.getSecretStatus(TENANT_MP, MessagingChannel.SMS);

            assertThat(response.getSecrets()).containsValues(SecretStatus.MISSING, SecretStatus.MISSING);
            assertThat(response.getKeyVersion()).isNull();
        }
    }

    // ── rewrapDataKeys ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rewrapDataKeys")
    class RewrapDataKeys {

        @Test
        @DisplayName("Re-wraps an outgoing key under the new one, leaving the data key recoverable")
        void rewrapsUnderTheActiveMasterKey() {
            String keyV2 = randomKey();
            Map<String, String> bothKeys = new LinkedHashMap<>();
            bothKeys.put("v1", keyV1);
            bothKeys.put("v2", keyV2);
            SecretCryptoService v1Only = cryptoWith("v1", new LinkedHashMap<>(Map.of("v1", keyV1)));
            TenantSecretKeyDTO oldRow = realKeyRow(1, "v1", v1Only);
            byte[] originalDataKey = v1Only.unwrapDataKey(oldRow.getWrappedKey(), TENANT_MP, 1, "v1");

            useCrypto(cryptoWith("v2", bothKeys));
            givenCurrentUser();
            when(secretRepository.findAllKeys()).thenReturn(List.of(oldRow));

            MessagingSecretRewrapResponseDTO response = service.rewrapDataKeys();

            ArgumentCaptor<String> rewrapped = ArgumentCaptor.forClass(String.class);
            verify(secretRepository).updateWrappedKey(eq(oldRow.getId()), rewrapped.capture(),
                    eq("v2"), eq(ADMIN_USER));
            // The same data key comes back out, now readable with v2 — so no ciphertext changed.
            assertThat(crypto.unwrapDataKey(rewrapped.getValue(), TENANT_MP, 1, "v2"))
                    .isEqualTo(originalDataKey);
            assertThat(response.getActiveMasterKeyId()).isEqualTo("v2");
            assertThat(response.getTotalKeys()).isEqualTo(1);
            assertThat(response.getRewrapped()).isEqualTo(1);
            assertThat(response.getAlreadyActive()).isZero();
            assertThat(response.getFailedTenantIds()).isEmpty();
        }

        @Test
        @DisplayName("A key already on the active master key is skipped, not rewritten")
        void skipsKeysAlreadyActive() {
            givenCurrentUser();
            when(secretRepository.findAllKeys()).thenReturn(List.of(realKeyRow(1, MASTER_V1, crypto)));

            MessagingSecretRewrapResponseDTO response = service.rewrapDataKeys();

            verify(secretRepository, never()).updateWrappedKey(anyInt(), anyString(), anyString(), anyInt());
            assertThat(response.getAlreadyActive()).isEqualTo(1);
            assertThat(response.getRewrapped()).isZero();
        }

        @Test
        @DisplayName("A row whose master key has left the environment is reported, and the rest still rewrap")
        void unreadableRowDoesNotBlockTheRotation() {
            // The scenario the counts exist for: v0 was removed before its rows were rewrapped.
            String keyV2 = randomKey();
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("v1", keyV1);
            keys.put("v2", keyV2);
            SecretCryptoService v1Only = cryptoWith("v1", new LinkedHashMap<>(Map.of("v1", keyV1)));
            TenantSecretKeyDTO readable = realKeyRow(1, "v1", v1Only);
            TenantSecretKeyDTO orphaned = TenantSecretKeyDTO.builder()
                    .id(999).tenantId(777).keyVersion(1)
                    .wrappedKey(readable.getWrappedKey()).masterKeyId("v0")
                    .status(TenantProviderSecretRepository.KEY_STATUS_ACTIVE).build();

            useCrypto(cryptoWith("v2", keys));
            givenCurrentUser();
            when(secretRepository.findAllKeys()).thenReturn(List.of(orphaned, readable));

            MessagingSecretRewrapResponseDTO response = service.rewrapDataKeys();

            assertThat(response.getRewrapped()).isEqualTo(1);
            assertThat(response.getFailedTenantIds()).containsExactly(777);
            verify(secretRepository).updateWrappedKey(eq(readable.getId()), anyString(), eq("v2"), eq(ADMIN_USER));
            verify(secretRepository, never()).updateWrappedKey(eq(999), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Answers 503 when the deployment has no master key")
        void requiresAMasterKey() {
            useCrypto(cryptoWith("", Map.of()));

            assertThatThrownBy(() -> service.rewrapDataKeys())
                    .isInstanceOf(SecretStoreUnavailableException.class);

            verify(secretRepository, never()).findAllKeys();
        }

        @Test
        @DisplayName("An empty platform rewraps nothing and reports zeroes")
        void emptyPlatform() {
            givenCurrentUser();
            when(secretRepository.findAllKeys()).thenReturn(List.of());

            MessagingSecretRewrapResponseDTO response = service.rewrapDataKeys();

            assertThat(response.getTotalKeys()).isZero();
            assertThat(response.getRewrapped()).isZero();
            assertThat(response.getFailedTenantIds()).isEmpty();
        }
    }

    // ── rotateTenantDataKey ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("rotateTenantDataKey")
    class RotateTenantDataKey {

        @Test
        @DisplayName("Takes the tenant's key lock before reading the version it will retire")
        void locksBeforeReadingTheKeyItRetires() {
            // The row read here is the one retired below, so two rotations at once would both read
            // version n, both retire it — the second WHERE status = 'ACTIVE' matching nothing — and
            // both insert version n+1.
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO oldKey = realKeyRow(1, MASTER_V1, crypto);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(oldKey));
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(1);
            when(secretRepository.findByTenant(TENANT_MP)).thenReturn(List.of());
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(2), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> TenantSecretKeyDTO.builder()
                            .id(2).tenantId(TENANT_MP).keyVersion(2)
                            .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1)
                            .status(TenantProviderSecretRepository.KEY_STATUS_ACTIVE).build());

            service.rotateTenantDataKey(TENANT_MP);

            InOrder order = inOrder(secretRepository);
            order.verify(secretRepository).lockKeys(TENANT_MP);
            order.verify(secretRepository).findActiveKey(TENANT_MP);
            order.verify(secretRepository).retireKey(oldKey.getId(), ADMIN_USER);
            order.verify(secretRepository).insertActiveKey(eq(TENANT_MP), eq(2), anyString(),
                    eq(MASTER_V1), eq(ADMIN_USER));
        }

        @Test
        @DisplayName("Re-encrypts the tenant's secrets under a new key version, unchanged in value")
        void reEncryptsUnderTheNewVersion() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO oldKey = realKeyRow(1, MASTER_V1, crypto);
            TenantProviderSecretDTO smsSecret =
                    realSecretRow(11, MessagingChannel.SMS, "authKey", AUTH_KEY_VALUE, oldKey);
            TenantProviderSecretDTO emailSecret =
                    realSecretRow(12, MessagingChannel.EMAIL, "apiKey", "SG.key", oldKey);

            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(oldKey));
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(1);
            when(secretRepository.findByTenant(TENANT_MP)).thenReturn(List.of(smsSecret, emailSecret));
            List<String> newWrapped = new ArrayList<>();
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(2), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> {
                        newWrapped.add(inv.getArgument(2));
                        return TenantSecretKeyDTO.builder().id(2).tenantId(TENANT_MP).keyVersion(2)
                                .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1)
                                .status(TenantProviderSecretRepository.KEY_STATUS_ACTIVE).build();
                    });

            MessagingSecretRotationResponseDTO response = service.rotateTenantDataKey(TENANT_MP);

            // Old version retired BEFORE the new one is inserted, or the partial unique index fires.
            verify(secretRepository).retireKey(oldKey.getId(), ADMIN_USER);
            ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> id = ArgumentCaptor.forClass(Integer.class);
            verify(secretRepository, times(2)).updateCiphertext(id.capture(), ciphertext.capture(),
                    eq(2), eq(ADMIN_USER));

            byte[] newDataKey = crypto.unwrapDataKey(newWrapped.get(0), TENANT_MP, 2, MASTER_V1);
            try {
                assertThat(crypto.decryptSecret(ciphertext.getAllValues().get(0), newDataKey, TENANT_MP,
                        MessagingChannel.SMS, "authKey", 2)).isEqualTo(AUTH_KEY_VALUE);
                assertThat(crypto.decryptSecret(ciphertext.getAllValues().get(1), newDataKey, TENANT_MP,
                        MessagingChannel.EMAIL, "apiKey", 2)).isEqualTo("SG.key");
            } finally {
                SecretCryptoService.zeroise(newDataKey);
            }
            assertThat(id.getAllValues()).containsExactly(11, 12);
            assertThat(response.getPreviousKeyVersion()).isEqualTo(1);
            assertThat(response.getNewKeyVersion()).isEqualTo(2);
            assertThat(response.getSecretsReEncrypted()).isEqualTo(2);
            assertThat(response.getChannels()).containsExactly(MessagingChannel.SMS, MessagingChannel.EMAIL);
        }

        @Test
        @DisplayName("The old ciphertext no longer decrypts under the new key version")
        void oldCiphertextIsNotReusable() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO oldKey = realKeyRow(1, MASTER_V1, crypto);
            TenantProviderSecretDTO secret =
                    realSecretRow(11, MessagingChannel.SMS, "authKey", AUTH_KEY_VALUE, oldKey);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(oldKey));
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(1);
            when(secretRepository.findByTenant(TENANT_MP)).thenReturn(List.of(secret));
            List<String> newWrapped = new ArrayList<>();
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(2), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> {
                        newWrapped.add(inv.getArgument(2));
                        return TenantSecretKeyDTO.builder().id(2).tenantId(TENANT_MP).keyVersion(2)
                                .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1).build();
                    });

            service.rotateTenantDataKey(TENANT_MP);

            byte[] newDataKey = crypto.unwrapDataKey(newWrapped.get(0), TENANT_MP, 2, MASTER_V1);
            try {
                assertThatThrownBy(() -> crypto.decryptSecret(secret.getCiphertext(), newDataKey, TENANT_MP,
                        MessagingChannel.SMS, "authKey", 2))
                        .isInstanceOf(org.arghyam.jalsoochak.tenant.exception.SecretCryptoException.class);
            } finally {
                SecretCryptoService.zeroise(newDataKey);
            }
        }

        @Test
        @DisplayName("A tenant with a key but no secrets rotates the key alone")
        void rotatesKeyWithNoSecrets() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO oldKey = realKeyRow(1, MASTER_V1, crypto);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(oldKey));
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(1);
            when(secretRepository.findByTenant(TENANT_MP)).thenReturn(List.of());
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(2), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> TenantSecretKeyDTO.builder().id(2).tenantId(TENANT_MP).keyVersion(2)
                            .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1).build());

            MessagingSecretRotationResponseDTO response = service.rotateTenantDataKey(TENANT_MP);

            assertThat(response.getSecretsReEncrypted()).isZero();
            assertThat(response.getChannels()).isEmpty();
            // Nothing cached changed, so nothing to evict.
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("A new version skips over a previously retired one rather than reusing it")
        void newVersionIsAboveEveryRetiredVersion() {
            givenOnboardedTenant();
            givenCurrentUser();
            TenantSecretKeyDTO active = realKeyRow(3, MASTER_V1, crypto);
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.of(active));
            when(secretRepository.findMaxKeyVersion(TENANT_MP)).thenReturn(5);
            when(secretRepository.findByTenant(TENANT_MP)).thenReturn(List.of());
            when(secretRepository.insertActiveKey(eq(TENANT_MP), eq(6), anyString(), eq(MASTER_V1), eq(ADMIN_USER)))
                    .thenAnswer(inv -> TenantSecretKeyDTO.builder().id(6).tenantId(TENANT_MP).keyVersion(6)
                            .wrappedKey(inv.getArgument(2)).masterKeyId(MASTER_V1).build());

            assertThat(service.rotateTenantDataKey(TENANT_MP).getNewKeyVersion()).isEqualTo(6);
        }

        @Test
        @DisplayName("A tenant that has never stored a secret has nothing to rotate")
        void noKeyToRotate() {
            givenOnboardedTenant();
            givenCurrentUser();
            when(secretRepository.findActiveKey(TENANT_MP)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotateTenantDataKey(TENANT_MP))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("no messaging secret key to rotate");
        }

        @Test
        @DisplayName("Answers 503 when the deployment has no master key")
        void requiresAMasterKey() {
            useCrypto(cryptoWith("", Map.of()));

            assertThatThrownBy(() -> service.rotateTenantDataKey(TENANT_MP))
                    .isInstanceOf(SecretStoreUnavailableException.class);

            verify(secretRepository, never()).findActiveKey(anyInt());
        }
    }
}
