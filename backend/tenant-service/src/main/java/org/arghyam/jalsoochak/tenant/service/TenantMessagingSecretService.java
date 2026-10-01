package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSecretsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRewrapResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingSecretRotationResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;

/**
 * MESSAGING-PROVIDER-SECRETS: writes and rotates the credentials a tenant's own email or
 * SMS provider account needs.
 *
 * <p>There is no read-a-value operation, by design (the store is write-only through the
 * API): {@link #getSecretStatus} reports SET or MISSING and nothing else. message-service
 * reads the ciphertext directly, with its own copy of the master key.
 *
 * <p>Every mutating method raises {@code TenantConfigUpdatedEvent} for the affected
 * channel's settings key, so message-service evicts its cached sender for that tenant. A
 * credential change that did not evict would keep the old credential in use until the TTL
 * expired.
 */
public interface TenantMessagingSecretService {

    /**
     * Stores the supplied secrets for one channel, creating the tenant's data key on first
     * use. Names absent from the request keep their current value.
     *
     * @throws org.arghyam.jalsoochak.tenant.exception.SecretStoreUnavailableException if the
     *                                                                                deployment has no master key
     */
    MessagingProviderSecretStatusResponseDTO setSecrets(Integer tenantId, MessagingChannel channel,
            SetMessagingProviderSecretsRequestDTO request);

    /** Soft-deletes every secret on a channel, so the tenant falls back to the system default. */
    MessagingProviderSecretStatusResponseDTO deleteSecrets(Integer tenantId, MessagingChannel channel);

    /** SET/MISSING per secret name the channel supports. Never returns a value. */
    MessagingProviderSecretStatusResponseDTO getSecretStatus(Integer tenantId, MessagingChannel channel);

    /**
     * Re-wraps every tenant data key under the active master key. Secret ciphertexts are
     * untouched, so this is one row per tenant regardless of how many secrets they hold.
     */
    MessagingSecretRewrapResponseDTO rewrapDataKeys();

    /**
     * Issues one tenant a new data key and re-encrypts that tenant's secrets under it,
     * retiring the old version. For when a state's credentials are believed exposed.
     */
    MessagingSecretRotationResponseDTO rotateTenantDataKey(Integer tenantId);
}
