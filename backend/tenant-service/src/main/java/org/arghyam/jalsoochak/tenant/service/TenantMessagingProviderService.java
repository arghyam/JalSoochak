package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSettingsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;

/**
 * MESSAGING-PROVIDER-SETTINGS: which provider account a tenant's email and SMS go through.
 *
 * <p>The settings half of the feature; {@link TenantMessagingSecretService} holds the credential
 * half. They are separate services because they are separate stores — settings are an ordinary
 * {@code tenant_config_master_table} row, credentials are encrypted rows under a per-tenant data
 * key — but they are written through one controller and read back together, because neither is
 * usable without the other.
 *
 * <p>Both keys are {@code managedValue}, so the generic {@code PUT /config} refuses them and this
 * is the only way in. That is what guarantees a stored settings value has been through the
 * provider, allowlist and TLS checks in {@link MessagingProviderSettingsValidator}.
 *
 * <p>Every mutating method raises {@code TenantConfigUpdatedEvent} for the channel it changed, so
 * message-service evicts its cached sender for that tenant.
 */
public interface TenantMessagingProviderService {

    /**
     * Stores the settings for the channels present in the request. A channel left out keeps its
     * current settings.
     *
     * @throws org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException if the settings do
     *                                                                            not pass validation
     */
    MessagingProviderConfigResponseDTO setProviderSettings(Integer tenantId,
            SetMessagingProviderSettingsRequestDTO request);

    /** Settings plus secret status for both channels. Never returns a secret value. */
    MessagingProviderConfigResponseDTO getProviderConfig(Integer tenantId);

    /**
     * Soft-deletes one channel's settings, so the tenant falls back to the system default provider.
     * Stored credentials are left alone — they are removed separately, through
     * {@code DELETE /messaging-providers/{channel}/secrets}.
     */
    MessagingProviderConfigResponseDTO deleteProviderSettings(Integer tenantId, MessagingChannel channel);
}
