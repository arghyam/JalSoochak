package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.EmailProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SmsProviderConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetMessagingProviderSettingsRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingChannelConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.MessagingProviderSecretStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.MessagingChannel;
import org.arghyam.jalsoochak.tenant.enums.SecretStatus;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.event.TenantConfigUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.MessagingProviderSettingsValidator;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingProviderService;
import org.arghyam.jalsoochak.tenant.service.TenantMessagingSecretService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MESSAGING-PROVIDER-SETTINGS: see {@link TenantMessagingProviderService}.
 *
 * <p>Nothing reads these settings yet. message-service gains the resolver in a later change, and
 * until it does a configured tenant behaves exactly as an unconfigured one — which is the point of
 * landing the write path first: the settings can be entered, reviewed and corrected before any
 * message depends on them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantMessagingProviderServiceImpl implements TenantMessagingProviderService {

    private final TenantCommonRepository tenantCommonRepository;
    private final TenantMessagingSecretService messagingSecretService;
    private final MessagingProviderSettingsValidator settingsValidator;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public MessagingProviderConfigResponseDTO setProviderSettings(Integer tenantId,
            SetMessagingProviderSettingsRequestDTO request) {
        if (request.isEmpty()) {
            throw new InvalidConfigValueException(
                    "At least one of 'email' or 'sms' must be present");
        }
        TenantResponseDTO tenant = requireConfigurableTenant(tenantId);

        // Validate everything before writing anything. A request naming both channels must not
        // leave the tenant with a new email provider and its old SMS one because the SMS block was
        // rejected halfway through — the transaction would roll the write back, but the response
        // would still describe a half-applied change.
        if (request.getEmail() != null) {
            settingsValidator.validateEmailSettings(request.getEmail());
        }
        if (request.getSms() != null) {
            settingsValidator.validateSmsSettings(request.getSms());
        }

        Integer currentUserId = resolveCurrentUserId();
        Set<MessagingChannel> changed = new LinkedHashSet<>();

        if (request.getEmail() != null) {
            writeSettings(tenantId, tenant, MessagingChannel.EMAIL, request.getEmail(), currentUserId);
            changed.add(MessagingChannel.EMAIL);
        }
        if (request.getSms() != null) {
            writeSettings(tenantId, tenant, MessagingChannel.SMS, request.getSms(), currentUserId);
            changed.add(MessagingChannel.SMS);
        }

        publishConfigUpdated(tenantId, tenant.getStateCode(), changed);
        return configOf(tenantId);
    }

    @Override
    public MessagingProviderConfigResponseDTO getProviderConfig(Integer tenantId) {
        requireConfigurableTenant(tenantId);
        return configOf(tenantId);
    }

    @Override
    @Transactional
    public MessagingProviderConfigResponseDTO deleteProviderSettings(Integer tenantId, MessagingChannel channel) {
        TenantResponseDTO tenant = requireConfigurableTenant(tenantId);
        Integer currentUserId = resolveCurrentUserId();

        TenantConfigKeyEnum configKey = settingsKeyOf(channel);
        String previousProvider = describeProvider(readSettings(tenantId, channel).orElse(null));
        int deleted = tenantCommonRepository.softDeleteConfig(tenantId, configKey.name(), currentUserId);

        log.info("Messaging provider settings deleted [actor={}, tenantId={}, stateCode={}, channel={}, "
                        + "provider={} -> none, rows={}]",
                SecurityUtils.getCurrentUserUuid(), tenantId, tenant.getStateCode(), channel,
                previousProvider, deleted);

        if (deleted > 0) {
            publishConfigUpdated(tenantId, tenant.getStateCode(), Set.of(channel));
        }
        return configOf(tenantId);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private void writeSettings(Integer tenantId, TenantResponseDTO tenant, MessagingChannel channel,
            ConfigValueDTO settings, Integer currentUserId) {
        TenantConfigKeyEnum configKey = settingsKeyOf(channel);
        String previousProvider = describeProvider(readSettings(tenantId, channel).orElse(null));

        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new InvalidConfigValueException("Failed to serialize config value for key: " + configKey, e);
        }
        tenantCommonRepository.upsertConfig(tenantId, configKey.name(), serialized, currentUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to upsert configuration for key: " + configKey));

        // The provider is logged, never the settings body: an SMTP username is not a credential but
        // it is half of one, and there is no reason for it to be in an INFO line.
        log.info("Messaging provider settings written [actor={}, tenantId={}, stateCode={}, channel={}, "
                        + "provider={} -> {}]",
                SecurityUtils.getCurrentUserUuid(), tenantId, tenant.getStateCode(), channel,
                previousProvider, describeProvider(settings));
    }

    private MessagingProviderConfigResponseDTO configOf(Integer tenantId) {
        return MessagingProviderConfigResponseDTO.builder()
                .tenantId(tenantId)
                .email(channelConfigOf(tenantId, MessagingChannel.EMAIL))
                .sms(channelConfigOf(tenantId, MessagingChannel.SMS))
                .build();
    }

    private MessagingChannelConfigResponseDTO channelConfigOf(Integer tenantId, MessagingChannel channel) {
        ConfigValueDTO settings = readSettings(tenantId, channel).orElse(null);
        MessagingProviderSecretStatusResponseDTO status =
                messagingSecretService.getSecretStatus(tenantId, channel);
        return MessagingChannelConfigResponseDTO.builder()
                .channel(channel)
                .settings(settings)
                .secrets(status.getSecrets())
                .keyVersion(status.getKeyVersion())
                .usable(isUsable(settings, status.getSecrets()))
                .build();
    }

    private Optional<ConfigValueDTO> readSettings(Integer tenantId, MessagingChannel channel) {
        TenantConfigKeyEnum configKey = settingsKeyOf(channel);
        Optional<ConfigDTO> stored = tenantCommonRepository.findConfigByTenantAndKey(tenantId, configKey.name());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(stored.get().getConfigValue(), configKey.getDtoClass()));
        } catch (JsonProcessingException e) {
            throw new InvalidConfigValueException("Malformed saved config value for key: " + configKey, e);
        }
    }

    /**
     * Settings alone do not make a tenant's provider usable — every credential the chosen provider
     * needs must be stored too, or message-service falls back to the system default. Reporting that
     * as one flag is what stops a state admin from writing settings, seeing 200, and concluding the
     * channel is live.
     */
    private static boolean isUsable(ConfigValueDTO settings, Map<String, SecretStatus> secrets) {
        Set<String> required = requiredSecretNames(settings);
        if (required.isEmpty()) {
            return false;
        }
        return required.stream().allMatch(name -> secrets.get(name) == SecretStatus.SET);
    }

    private static Set<String> requiredSecretNames(ConfigValueDTO settings) {
        if (settings instanceof EmailProviderConfigDTO email && email.getProvider() != null) {
            return email.getProvider().getRequiredSecretNames();
        }
        if (settings instanceof SmsProviderConfigDTO sms && sms.getProvider() != null) {
            return sms.getProvider().getRequiredSecretNames();
        }
        return Set.of();
    }

    /** The provider name for an audit line, or {@code "none"} when the channel has no settings. */
    private static String describeProvider(ConfigValueDTO settings) {
        if (settings instanceof EmailProviderConfigDTO email && email.getProvider() != null) {
            return email.getProvider().getWireName();
        }
        if (settings instanceof SmsProviderConfigDTO sms && sms.getProvider() != null) {
            return sms.getProvider().getWireName();
        }
        return "none";
    }

    /**
     * Resolves the channel's settings key by name rather than holding a reference to the constant.
     * {@link MessagingChannel} was written before these two constants existed and still carries the
     * key as a string, because a secret write has to raise {@code TENANT_CONFIG_UPDATED} under it.
     * Going through {@code valueOf} here means a rename on either side fails loudly the first time
     * a settings endpoint is called, rather than quietly writing a config key message-service does
     * not evict on.
     */
    private static TenantConfigKeyEnum settingsKeyOf(MessagingChannel channel) {
        return TenantConfigKeyEnum.valueOf(channel.getSettingsConfigKey());
    }

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
     * The same two guards {@code setTenantConfigs} applies.
     *
     * <p>Duplicated from {@code TenantManagementServiceImpl}'s private helpers, as
     * {@code TenantMessagingSecretServiceImpl} already does, rather than extracted: that class has a
     * dozen call sites and is under active change, so hoisting them is a refactor of its own.
     */
    private TenantResponseDTO requireConfigurableTenant(Integer tenantId) {
        if (tenantId != null && tenantId.equals(TenantConstants.SYSTEM_TENANT_ID)) {
            throw new IllegalArgumentException("Operation not permitted on the system tenant.");
        }
        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));
        if (TenantStatusEnum.REGISTERED.name().equals(tenant.getStatus())) {
            throw new ResourceNotFoundException("Tenant with tenantId " + tenantId + " is not onboarded");
        }
        return tenant;
    }

    private Integer resolveCurrentUserId() {
        String uuid = SecurityUtils.getCurrentUserUuid();
        return tenantCommonRepository.findUserIdByUuid(uuid)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }
}
