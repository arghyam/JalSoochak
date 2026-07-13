package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.IncludedWorkStatusesConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetSystemConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.SystemConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.SystemConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigKeyException;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemManagementServiceImpl implements SystemManagementService {

    /** Sentinel stateCode carried on national-default (tenant-0) events; stateCode is informational only. */
    private static final String NATIONAL_STATE_CODE = "NATIONAL";

    private final TenantCommonRepository tenantCommonRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public SystemConfigResponseDTO getSystemConfigs(Set<SystemConfigKeyEnum> keys) {
        log.info("Fetching system configurations [keys={}]", keys);
        Set<SystemConfigKeyEnum> effectiveKeys = (keys == null || keys.isEmpty())
                ? EnumSet.allOf(SystemConfigKeyEnum.class)
                : keys;

        List<ConfigDTO> configs = tenantCommonRepository.findConfigsByTenantId(TenantConstants.SYSTEM_TENANT_ID);
        Map<SystemConfigKeyEnum, ConfigValueDTO> configMap = new HashMap<>();

        for (ConfigDTO cfg : configs) {
            try {
                SystemConfigKeyEnum key = SystemConfigKeyEnum.valueOf(cfg.getConfigKey());
                if (effectiveKeys.contains(key)) {
                    configMap.put(key, objectMapper.readValue(cfg.getConfigValue(), key.getDtoClass()));
                }
            } catch (IllegalArgumentException e) {
                log.error("Invalid system config key: {}", cfg.getConfigKey(), e);
                throw new InvalidConfigKeyException("Invalid system config key: " + cfg.getConfigKey(), e);
            } catch (JsonProcessingException e) {
                log.error("Malformed system config value for key [key={}]", cfg.getConfigKey(), e);
                throw new InvalidConfigValueException("Malformed config value for key: " + cfg.getConfigKey(), e);
            }
        }

        return SystemConfigResponseDTO.builder().configs(configMap).build();
    }

    @Override
    @Transactional
    public SystemConfigResponseDTO setSystemConfigs(SetSystemConfigRequestDTO request) {
        log.info("Setting system configurations");
        String currentUuid = SecurityUtils.getCurrentUserUuid();
        Integer currentUserId = tenantCommonRepository.findUserIdByUuid(currentUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found for uuid: " + currentUuid));

        Map<SystemConfigKeyEnum, ConfigValueDTO> results = new HashMap<>();

        for (Map.Entry<SystemConfigKeyEnum, JsonNode> entry : request.getConfigs().entrySet()) {
            SystemConfigKeyEnum key = entry.getKey();
            ConfigValueDTO dto;
            try {
                dto = objectMapper.treeToValue(entry.getValue(), key.getDtoClass());
            } catch (JsonProcessingException e) {
                throw new InvalidConfigValueException(
                        "Invalid value for config key " + key + ": " + e.getMessage(), e);
            }

            if (dto instanceof ChannelListConfigDTO channelDto) {
                channelDto.setDegraded(null);
                channelDto.setRemovedChannels(null);
            }

            String serialized;
            try {
                serialized = objectMapper.writeValueAsString(dto);
            } catch (JsonProcessingException e) {
                throw new InvalidConfigValueException(
                        "Failed to serialize config value for key: " + key, e);
            }

            ConfigDTO cfg = tenantCommonRepository
                    .upsertConfig(TenantConstants.SYSTEM_TENANT_ID, key.name(), serialized, currentUserId)
                    .orElseThrow(() -> new RuntimeException("Failed to upsert system config: " + key));

            try {
                results.put(SystemConfigKeyEnum.valueOf(cfg.getConfigKey()),
                        objectMapper.readValue(cfg.getConfigValue(), key.getDtoClass()));
            } catch (JsonProcessingException e) {
                throw new InvalidConfigValueException(
                        "Malformed saved config value for key: " + key, e);
            }
        }

        if (request.getConfigs().containsKey(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES)) {
            IncludedWorkStatusesConfigDTO dto =
                    (IncludedWorkStatusesConfigDTO) results.get(SystemConfigKeyEnum.INCLUDED_WORK_STATUSES);
            // Enforced validation for JsonNode-bound configs (bean validation does not run on treeToValue).
            List<Integer> workStatuses = dto.validatedWorkStatuses();
            eventPublisher.publishEvent(new IncludedWorkStatusesUpdatedEvent(
                    TenantConstants.SYSTEM_TENANT_ID, NATIONAL_STATE_CODE, workStatuses));
        }

        return SystemConfigResponseDTO.builder().configs(results).build();
    }

    @Override
    public List<String> getSystemSupportedChannels() {
        log.info("Fetching system supported channels");
        List<ConfigDTO> configs = tenantCommonRepository.findConfigsByTenantId(TenantConstants.SYSTEM_TENANT_ID);
        return configs.stream()
                .filter(cfg -> SystemConfigKeyEnum.SYSTEM_SUPPORTED_CHANNELS.name().equals(cfg.getConfigKey()))
                .findFirst()
                .map(cfg -> {
                    ChannelListConfigDTO parsed;
                    try {
                        parsed = objectMapper.readValue(cfg.getConfigValue(), ChannelListConfigDTO.class);
                    } catch (JsonProcessingException e) {
                        log.error("Malformed SYSTEM_SUPPORTED_CHANNELS config value", e);
                        throw new InvalidConfigValueException("Malformed SYSTEM_SUPPORTED_CHANNELS config value", e);
                    }
                    if (parsed.getChannels() == null) {
                        log.error("SYSTEM_SUPPORTED_CHANNELS config is missing channels field [value={}]", cfg.getConfigValue());
                        throw new InvalidConfigValueException("SYSTEM_SUPPORTED_CHANNELS config is missing channels field");
                    }
                    return parsed.getChannels();
                })
                .orElse(Collections.emptyList());
    }
}
