package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
import org.arghyam.jalsoochak.tenant.exception.SecretCryptoException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantProviderSecretRepository;
import org.arghyam.jalsoochak.tenant.service.SecretCryptoService;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MESSAGING-PROVIDER-SECRETS: see {@link TenantMessagingSecretService}.
 *
 * <p>Every secret value is in scope for as short a time as possible: the plaintext arrives
 * on the request, is encrypted, and is never held in a field or a cache. The tenant's data
 * key is unwrapped per operation and zeroised in a {@code finally} block, so a heap dump
 * taken between two requests contains no usable key material.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantMessagingSecretServiceImpl implements TenantMessagingSecretService {

    private final TenantCommonRepository tenantCommonRepository;
    private final TenantProviderSecretRepository secretRepository;
    private final SecretCryptoService secretCryptoService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public MessagingProviderSecretStatusResponseDTO setSecrets(Integer tenantId, MessagingChannel channel,
            SetMessagingProviderSecretsRequestDTO request) {
        secretCryptoService.requireConfigured();
        TenantResponseDTO tenant = requireConfigurableTenant(tenantId);
        Map<String, String> values = normaliseAndValidate(channel, request);
        Integer currentUserId = resolveCurrentUserId();

        TenantSecretKeyDTO key = getOrCreateActiveKey(tenantId, currentUserId);
        byte[] dataKey = null;
        try {
            dataKey = secretCryptoService.unwrapDataKey(
                    key.getWrappedKey(), tenantId, key.getKeyVersion(), key.getMasterKeyId());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String ciphertext = secretCryptoService.encryptSecret(
                        entry.getValue(), dataKey, tenantId, channel, entry.getKey(), key.getKeyVersion());
                secretRepository.upsertSecret(
                        tenantId, channel, entry.getKey(), ciphertext, key.getKeyVersion(), currentUserId);
            }
        } finally {
            SecretCryptoService.zeroise(dataKey);
        }

        // Names only — S-4. The actor is logged so a credential change is attributable.
        log.info("Messaging provider secrets written [actor={}, tenantId={}, stateCode={}, channel={}, "
                        + "secretNames={}, keyVersion={}]",
                SecurityUtils.getCurrentUserUuid(), tenantId, tenant.getStateCode(), channel,
                values.keySet(), key.getKeyVersion());

        publishConfigUpdated(tenantId, tenant.getStateCode(), Set.of(channel));
        return statusOf(tenantId, channel);
    }

    @Override
    @Transactional
    public MessagingProviderSecretStatusResponseDTO deleteSecrets(Integer tenantId, MessagingChannel channel) {
        // No requireConfigured(): removing a credential must stay possible even if the master
        // key has gone missing. Deleting ciphertext needs no key.
        TenantResponseDTO tenant = requireConfigurableTenant(tenantId);
        Integer currentUserId = resolveCurrentUserId();

        Set<String> removed = secretRepository.findSecretNames(tenantId, channel);
        int deleted = secretRepository.softDeleteByTenantAndChannel(tenantId, channel, currentUserId);

        log.info("Messaging provider secrets deleted [actor={}, tenantId={}, stateCode={}, channel={}, "
                        + "secretNames={}, rows={}]",
                SecurityUtils.getCurrentUserUuid(), tenantId, tenant.getStateCode(), channel, removed, deleted);

        if (deleted > 0) {
            publishConfigUpdated(tenantId, tenant.getStateCode(), Set.of(channel));
        }
        return statusOf(tenantId, channel);
    }

    @Override
    public MessagingProviderSecretStatusResponseDTO getSecretStatus(Integer tenantId, MessagingChannel channel) {
        requireConfigurableTenant(tenantId);
        return statusOf(tenantId, channel);
    }

    @Override
    @Transactional
    public MessagingSecretRewrapResponseDTO rewrapDataKeys() {
        secretCryptoService.requireConfigured();
        String activeMasterKeyId = secretCryptoService.getActiveMasterKeyId();
        Integer currentUserId = resolveCurrentUserId();

        List<TenantSecretKeyDTO> keys = secretRepository.findAllKeys();
        int rewrapped = 0;
        int alreadyActive = 0;
        Set<Integer> failedTenantIds = new LinkedHashSet<>();

        for (TenantSecretKeyDTO key : keys) {
            if (activeMasterKeyId.equals(key.getMasterKeyId())) {
                alreadyActive++;
                continue;
            }
            byte[] dataKey = null;
            try {
                dataKey = secretCryptoService.unwrapDataKey(
                        key.getWrappedKey(), key.getTenantId(), key.getKeyVersion(), key.getMasterKeyId());
                String rewrappedKey = secretCryptoService.wrapDataKey(
                        dataKey, key.getTenantId(), key.getKeyVersion(), activeMasterKeyId);
                secretRepository.updateWrappedKey(key.getId(), rewrappedKey, activeMasterKeyId, currentUserId);
                rewrapped++;
            } catch (SecretCryptoException e) {
                // Recorded and skipped rather than thrown: one row wrapped under a master key that
                // has already left the environment must not stop the rest of the rotation. The
                // skipped row performed no write, so the transaction stays consistent.
                failedTenantIds.add(key.getTenantId());
                log.error("Failed to re-wrap tenant data key [tenantId={}, keyVersion={}, masterKeyId={}]: {}",
                        key.getTenantId(), key.getKeyVersion(), key.getMasterKeyId(), e.getMessage());
            } finally {
                SecretCryptoService.zeroise(dataKey);
            }
        }

        log.info("Messaging secret master-key rewrap complete [actor={}, activeMasterKeyId={}, totalKeys={}, "
                        + "rewrapped={}, alreadyActive={}, failedTenants={}]",
                SecurityUtils.getCurrentUserUuid(), activeMasterKeyId, keys.size(), rewrapped, alreadyActive,
                failedTenantIds);

        return MessagingSecretRewrapResponseDTO.builder()
                .activeMasterKeyId(activeMasterKeyId)
                .totalKeys(keys.size())
                .rewrapped(rewrapped)
                .alreadyActive(alreadyActive)
                .failedTenantIds(new ArrayList<>(failedTenantIds))
                .build();
    }

    @Override
    @Transactional
    public MessagingSecretRotationResponseDTO rotateTenantDataKey(Integer tenantId) {
        secretCryptoService.requireConfigured();
        TenantResponseDTO tenant = requireConfigurableTenant(tenantId);
        Integer currentUserId = resolveCurrentUserId();

        TenantSecretKeyDTO current = secretRepository.findActiveKey(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " has no messaging secret key to rotate"));

        // Retire first: uq_tenant_secret_key_active allows one ACTIVE row per tenant, so the
        // insert below would violate it otherwise.
        secretRepository.retireKey(current.getId(), currentUserId);

        int newVersion = secretRepository.findMaxKeyVersion(tenantId) + 1;
        String activeMasterKeyId = secretCryptoService.getActiveMasterKeyId();

        byte[] oldDataKey = null;
        byte[] newDataKey = null;
        Set<MessagingChannel> touchedChannels = new LinkedHashSet<>();
        int reEncrypted = 0;
        try {
            newDataKey = secretCryptoService.generateDataKey();
            String wrapped = secretCryptoService.wrapDataKey(newDataKey, tenantId, newVersion, activeMasterKeyId);
            secretRepository.insertActiveKey(tenantId, newVersion, wrapped, activeMasterKeyId, currentUserId);

            List<TenantProviderSecretDTO> secrets = secretRepository.findByTenant(tenantId);
            if (!secrets.isEmpty()) {
                oldDataKey = secretCryptoService.unwrapDataKey(
                        current.getWrappedKey(), tenantId, current.getKeyVersion(), current.getMasterKeyId());
            }
            for (TenantProviderSecretDTO secret : secrets) {
                // Decrypting under the row's own key version, then re-encrypting under the new
                // one, is what moves the AAD across: the ciphertext is bound to the version it
                // is stored with, so the key_version update and the re-encryption cannot be
                // separated.
                String value = secretCryptoService.decryptSecret(secret.getCiphertext(), oldDataKey, tenantId,
                        secret.getChannel(), secret.getSecretName(), secret.getKeyVersion());
                String ciphertext = secretCryptoService.encryptSecret(value, newDataKey, tenantId,
                        secret.getChannel(), secret.getSecretName(), newVersion);
                secretRepository.updateCiphertext(secret.getId(), ciphertext, newVersion, currentUserId);
                touchedChannels.add(secret.getChannel());
                reEncrypted++;
            }
        } finally {
            SecretCryptoService.zeroise(oldDataKey);
            SecretCryptoService.zeroise(newDataKey);
        }

        log.info("Messaging secret data key rotated [actor={}, tenantId={}, stateCode={}, previousKeyVersion={}, "
                        + "newKeyVersion={}, secretsReEncrypted={}, channels={}]",
                SecurityUtils.getCurrentUserUuid(), tenantId, tenant.getStateCode(), current.getKeyVersion(),
                newVersion, reEncrypted, touchedChannels);

        publishConfigUpdated(tenantId, tenant.getStateCode(), touchedChannels);

        return MessagingSecretRotationResponseDTO.builder()
                .tenantId(tenantId)
                .previousKeyVersion(current.getKeyVersion())
                .newKeyVersion(newVersion)
                .secretsReEncrypted(reEncrypted)
                .channels(new ArrayList<>(touchedChannels))
                .build();
    }

    // ── internals ───────────────────────────────────────────────────────────────

    /**
     * Rejects names the channel does not own, and trims values.
     *
     * <p>Trimming is not cosmetic: a credential pasted into Swagger or a shell commonly
     * carries a trailing newline, and a provider would reject the resulting key with an
     * authentication error that looks nothing like a whitespace problem.
     */
    private Map<String, String> normaliseAndValidate(MessagingChannel channel,
            SetMessagingProviderSecretsRequestDTO request) {
        Map<String, String> normalised = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : request.getSecrets().entrySet()) {
            String name = entry.getKey() == null ? null : entry.getKey().trim();
            if (!channel.supportsSecret(name)) {
                // Echoes the name, never the value.
                throw new IllegalArgumentException("Unknown secret name '" + name + "' for channel "
                        + channel + ". Accepted names: " + channel.getSecretNames());
            }
            String value = entry.getValue().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Secret value for '" + name + "' must not be blank");
            }
            normalised.put(name, value);
        }
        return normalised;
    }

    /** Creates the tenant's first data key on demand, so writing a secret needs no provisioning step. */
    private TenantSecretKeyDTO getOrCreateActiveKey(Integer tenantId, Integer currentUserId) {
        return secretRepository.findActiveKey(tenantId).orElseGet(() -> {
            String masterKeyId = secretCryptoService.getActiveMasterKeyId();
            int version = secretRepository.findMaxKeyVersion(tenantId) + 1;
            byte[] dataKey = null;
            try {
                dataKey = secretCryptoService.generateDataKey();
                String wrapped = secretCryptoService.wrapDataKey(dataKey, tenantId, version, masterKeyId);
                TenantSecretKeyDTO created = secretRepository.insertActiveKey(
                        tenantId, version, wrapped, masterKeyId, currentUserId);
                log.info("Created messaging secret data key [tenantId={}, keyVersion={}, masterKeyId={}]",
                        tenantId, version, masterKeyId);
                return created;
            } finally {
                SecretCryptoService.zeroise(dataKey);
            }
        });
    }

    private MessagingProviderSecretStatusResponseDTO statusOf(Integer tenantId, MessagingChannel channel) {
        List<TenantProviderSecretDTO> stored = secretRepository.findByTenantAndChannel(tenantId, channel);
        Set<String> storedNames = new LinkedHashSet<>();
        Integer keyVersion = null;
        for (TenantProviderSecretDTO secret : stored) {
            storedNames.add(secret.getSecretName());
            keyVersion = secret.getKeyVersion();
        }
        Map<String, SecretStatus> statuses = new TreeMap<>();
        for (String name : channel.getSecretNames()) {
            statuses.put(name, storedNames.contains(name) ? SecretStatus.SET : SecretStatus.MISSING);
        }
        return MessagingProviderSecretStatusResponseDTO.builder()
                .tenantId(tenantId)
                .channel(channel)
                .secrets(statuses)
                .keyVersion(keyVersion)
                .build();
    }

    /**
     * Raises the same event the generic config API raises, under the channel's settings key,
     * so message-service's cache eviction needs no secret-specific path.
     */
    private void publishConfigUpdated(Integer tenantId, String stateCode, Set<MessagingChannel> channels) {
        if (channels.isEmpty()) {
            return;
        }
        Set<String> configKeys = new LinkedHashSet<>();
        for (MessagingChannel channel : channels) {
            configKeys.add(channel.getSettingsConfigKey());
        }
        eventPublisher.publishEvent(new TenantConfigUpdatedEvent(tenantId, stateCode, configKeys));
    }

    /**
     * The same two guards {@code setTenantConfigs} applies — the system tenant holds platform
     * defaults and has no provider account of its own, and a tenant that was pre-seeded but
     * never onboarded is treated as not found.
     *
     * <p>Duplicated from {@code TenantManagementServiceImpl}'s private helpers rather than
     * extracted: that class has a dozen call sites and is under active change, so hoisting
     * them is a refactor of its own.
     */
    private TenantResponseDTO requireConfigurableTenant(Integer tenantId) {
        if (tenantId != null && tenantId.equals(TenantConstants.SYSTEM_TENANT_ID)) {
            throw new IllegalArgumentException("Operation not permitted on the system tenant.");
        }
        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));
        if (TenantStatusEnum.REGISTERED.name().equals(tenant.getStatus())) {
            throw new ResourceNotFoundException(
                    "Tenant with tenantId " + tenantId + " is not onboarded");
        }
        return tenant;
    }

    private Integer resolveCurrentUserId() {
        String uuid = SecurityUtils.getCurrentUserUuid();
        return tenantCommonRepository.findUserIdByUuid(uuid)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }
}
