package org.arghyam.jalsoochak.tenant.service.serviceImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.arghyam.jalsoochak.tenant.config.TenantDefaultsProperties;
import org.arghyam.jalsoochak.tenant.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ChannelListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LogoSource;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantLogoResult;
import org.arghyam.jalsoochak.tenant.dto.internal.LanguageConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LanguageListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ReasonListConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.request.CreateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.SetTenantConfigRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.request.UpdateTenantRequestDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyEditConstraintsResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationHierarchyResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.LocationResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigStatusResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantSummaryResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.ConfigStatusEnum;
import org.arghyam.jalsoochak.tenant.enums.RegionTypeEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum.ConfigType;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.event.TenantCreatedEvent;
import org.arghyam.jalsoochak.tenant.event.TenantDeactivatedEvent;
import org.arghyam.jalsoochak.tenant.event.TenantUpdatedEvent;
import org.arghyam.jalsoochak.tenant.exception.ConfigurationException;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigKeyException;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.exception.StorageException;
import org.arghyam.jalsoochak.tenant.storage.ObjectStorageService;
import org.springframework.web.multipart.MultipartFile;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.repository.TenantSchemaRepository;
import org.arghyam.jalsoochak.tenant.service.SystemManagementService;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.arghyam.jalsoochak.tenant.service.TenantSchedulerManager;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantConstants;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantManagementServiceImpl implements TenantManagementService {

    private final TenantCommonRepository tenantCommonRepository;
    private final TenantSchemaRepository tenantSchemaRepository;
    private final ObjectMapper objectMapper;
    private final TenantDefaultsProperties tenantDefaults;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantSchedulerManager schedulerManager;
    private final ObjectStorageService objectStorageService;
    private final SystemManagementService systemManagementService;


    // TODO: Re-enable "image/svg+xml" only after implementing SVG sanitization and serving from an isolated origin.
    private static final Map<String, String> ALLOWED_LOGO_TYPES =
            Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    @Override
    @Transactional
    public TenantResponseDTO createTenant(CreateTenantRequestDTO request) {
        log.info("Creating tenant – stateCode: {}, name: {}", request.getStateCode(), request.getName());

        tenantCommonRepository.findByStateCode(request.getStateCode()).ifPresent(existing -> {
            throw new IllegalStateException(
                    "Tenant with state code '" + request.getStateCode() + "' already exists");
        });

        Integer currentUserId = resolveCurrentUserId();

        TenantResponseDTO tenant = tenantCommonRepository.createTenant(request, currentUserId)
                .orElseThrow(() -> new RuntimeException("Tenant creation failed – no record returned"));
        log.info("Tenant record created in common_schema with id: {}", tenant.getId());

        String schemaName = "tenant_" + request.getStateCode().toLowerCase();
        tenantCommonRepository.provisionTenantSchema(schemaName);
        log.info("Tenant schema '{}' provisioned successfully", schemaName);

        setDefaultConfigs(tenant, schemaName, currentUserId);
        eventPublisher.publishEvent(new TenantCreatedEvent(tenant, schemaName));

        return tenant;
    }

    @Override
    @Transactional
    public TenantResponseDTO updateTenant(Integer tenantId, UpdateTenantRequestDTO request) {
        log.info("Updating tenant [id={}]", tenantId);
        validateNotSystemTenant(tenantId);

        tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        if (TenantStatusEnum.INACTIVE.name().equalsIgnoreCase(request.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot deactivate tenant via this endpoint. Use the deactivateTenant endpoint instead.");
        }

        Integer currentUserId = resolveCurrentUserId();

        TenantResponseDTO updated = tenantCommonRepository.updateTenant(tenantId, request, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));
        log.info("Tenant [id={}] updated successfully", tenantId);
        eventPublisher.publishEvent(new TenantUpdatedEvent(updated));
        return updated;
    }

    @Override
    @Transactional
    public void deactivateTenant(Integer tenantId) {
        log.info("Deactivating tenant [id={}]", tenantId);
        validateNotSystemTenant(tenantId);

        tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        Integer currentUserId = resolveCurrentUserId();

        tenantCommonRepository.deactivateTenant(tenantId, currentUserId);
        log.info("Tenant [id={}] deactivated successfully", tenantId);

        tenantCommonRepository.findById(tenantId)
                .ifPresent(tenant -> eventPublisher.publishEvent(new TenantDeactivatedEvent(tenant)));
    }

    @Override
    public PageResponseDTO<TenantResponseDTO> getAllTenants(int page, int size, TenantStatusEnum status, String search) {
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        long offset = (long) page * size;
        List<TenantResponseDTO> tenants = tenantCommonRepository.findAll(size, offset, status, normalizedSearch);
        long totalElements = tenantCommonRepository.countAllTenants(status, normalizedSearch);
        return PageResponseDTO.of(tenants, totalElements, page, size);
    }

    @Override
    public TenantSummaryResponseDTO getTenantSummary() {
        log.info("Fetching tenant status summary");
        return tenantCommonRepository.getTenantSummary();
    }

    @Override
    public TenantConfigResponseDTO getTenantConfigs(Integer tenantId, Set<TenantConfigKeyEnum> keys) {
        log.info("Fetching tenant configurations [id={}, keys={}]", tenantId, keys);
        validateNotSystemTenant(tenantId);
        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        Set<TenantConfigKeyEnum> effectiveKeys = (keys == null || keys.isEmpty())
                ? EnumSet.allOf(TenantConfigKeyEnum.class)
                : keys;

        Map<TenantConfigKeyEnum, ConfigValueDTO> configMap = new HashMap<>();

        List<ConfigDTO> configs = tenantCommonRepository.findConfigsByTenantId(tenantId);
        for (ConfigDTO cfg : configs) {
            try {
                TenantConfigKeyEnum key = TenantConfigKeyEnum.valueOf(cfg.getConfigKey());
                if (effectiveKeys.contains(key)) {
                    configMap.put(key, objectMapper.readValue(cfg.getConfigValue(), key.getDtoClass()));
                }
            } catch (IllegalArgumentException e) {
                log.error("Invalid tenant config key [key={}]", cfg.getConfigKey(), e);
                throw new InvalidConfigKeyException("Invalid tenant config key: " + cfg.getConfigKey(), e);
            } catch (JsonProcessingException e) {
                log.error("Malformed config value for key [key={}]", cfg.getConfigKey(), e);
                throw new InvalidConfigValueException("Malformed config value for key: " + cfg.getConfigKey(), e);
            }
        }

        String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();

        if (effectiveKeys.contains(TenantConfigKeyEnum.SUPPORTED_LANGUAGES)) {
            List<LanguageConfigDTO> langs = tenantSchemaRepository.getSupportedLanguages(schemaName);
            if (langs != null && !langs.isEmpty()) {
                configMap.put(TenantConfigKeyEnum.SUPPORTED_LANGUAGES,
                        LanguageListConfigDTO.builder().languages(langs).build());
            }
        }

        if (configMap.containsKey(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS)) {
            ChannelListConfigDTO stored = (ChannelListConfigDTO) configMap.get(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS);
            if (stored == null || stored.getChannels() == null) {
                throw new InvalidConfigValueException("Stored TENANT_SUPPORTED_CHANNELS config is missing channels field");
            }
            Set<String> systemChannels = new HashSet<>(fetchSystemSupportedChannels());
            List<String> effective = stored.getChannels().stream()
                    .filter(systemChannels::contains)
                    .collect(Collectors.toList());
            List<String> removed = stored.getChannels().stream()
                    .filter(ch -> !systemChannels.contains(ch))
                    .collect(Collectors.toList());
            boolean degraded = !removed.isEmpty();
            configMap.put(TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS,
                    ChannelListConfigDTO.builder()
                            .channels(effective)
                            .degraded(degraded ? Boolean.TRUE : null)
                            .removedChannels(degraded ? removed : null)
                            .build());
        }

        return TenantConfigResponseDTO.builder()
                .tenantId(tenantId)
                .configs(configMap)
                .build();
    }

    @Override
    @Transactional
    public TenantConfigResponseDTO setTenantConfigs(Integer tenantId, SetTenantConfigRequestDTO request) {
        log.info("Setting tenant configurations [id={}]", tenantId);
        validateNotSystemTenant(tenantId);
        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        Integer currentUserId = resolveCurrentUserId();

        Map<TenantConfigKeyEnum, ConfigValueDTO> results = new HashMap<>();

        for (Map.Entry<TenantConfigKeyEnum, JsonNode> entry : request.getConfigs().entrySet()) {
            TenantConfigKeyEnum key = entry.getKey();
            if (key.isManagedValue()) {
                throw new InvalidConfigKeyException(
                        key + " is managed by a dedicated endpoint and cannot be set via the generic config API.");
            }
            ConfigValueDTO dto;
            try {
                dto = objectMapper.treeToValue(entry.getValue(), key.getDtoClass());
            } catch (JsonProcessingException e) {
                throw new InvalidConfigValueException(
                        "Invalid value for config key " + key + ": " + e.getMessage(), e);
            }

            if (key == TenantConfigKeyEnum.TENANT_SUPPORTED_CHANNELS) {
                ChannelListConfigDTO channelDto = (ChannelListConfigDTO) dto;
                channelDto.setDegraded(null);
                channelDto.setRemovedChannels(null);
                Set<String> systemChannels = new HashSet<>(fetchSystemSupportedChannels());
                if (systemChannels.isEmpty()) {
                    throw new InvalidConfigValueException(
                            "The system currently does not support any channels.");
                }
                List<String> invalid = channelDto.getChannels().stream()
                        .filter(ch -> !systemChannels.contains(ch))
                        .collect(Collectors.toList());
                if (!invalid.isEmpty()) {
                    throw new InvalidConfigValueException(
                            "Channels not supported at system level: " + invalid);
                }
            }

            if (key.getType() == ConfigType.GENERIC) {
                String serialized;
                try {
                    serialized = objectMapper.writeValueAsString(dto);
                } catch (JsonProcessingException e) {
                    throw new InvalidConfigValueException(
                            "Failed to serialize config value for key: " + key, e);
                }
                ConfigDTO cfg = tenantCommonRepository
                        .upsertConfig(tenantId, key.name(), serialized, currentUserId)
                        .orElseThrow(() -> new RuntimeException(
                                "Failed to upsert configuration for key: " + key));
                try {
                    results.put(TenantConfigKeyEnum.valueOf(cfg.getConfigKey()),
                            objectMapper.readValue(cfg.getConfigValue(), key.getDtoClass()));
                } catch (JsonProcessingException e) {
                    throw new InvalidConfigValueException(
                            "Malformed saved config value for key: " + key, e);
                }
            } else {
                String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();
                handleSpecializedConfig(schemaName, key, dto, currentUserId);
                results.put(key, dto);
            }
        }

        // Only reschedule when a schedule-bearing key was actually updated, and defer
        // the call to after the transaction commits so a bad schedule config cannot
        // roll back an otherwise-valid config write (e.g. SUPPORTED_LANGUAGES).
        Set<TenantConfigKeyEnum> scheduleKeys = EnumSet.of(
                TenantConfigKeyEnum.PUMP_OPERATOR_REMINDER_NUDGE_TIME,
                TenantConfigKeyEnum.FIELD_STAFF_ESCALATION_RULES);
        boolean hasScheduleKey = request.getConfigs().keySet().stream()
                .anyMatch(scheduleKeys::contains);
        if (hasScheduleKey) {
            final int finalTenantId = tenantId;
            final String finalStateCode = tenant.getStateCode();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        schedulerManager.rescheduleForTenant(finalTenantId, finalStateCode);
                    }
                });
            } else {
                schedulerManager.rescheduleForTenant(finalTenantId, finalStateCode);
            }
        }

        return TenantConfigResponseDTO.builder()
                .tenantId(tenantId)
                .configs(results)
                .build();
    }

    @Override
    public TenantConfigStatusResponseDTO getTenantConfigStatus(Integer tenantId) {
        log.info("Fetching config status [id={}]", tenantId);
        validateNotSystemTenant(tenantId);

        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        // Collect configured GENERIC keys from DB
        Set<String> configuredKeys = tenantCommonRepository.findConfigsByTenantId(tenantId)
                .stream()
                .map(ConfigDTO::getConfigKey)
                .collect(Collectors.toSet());

        // Check SPECIALIZED key: SUPPORTED_LANGUAGES
        String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();
        List<LanguageConfigDTO> langs = tenantSchemaRepository.getSupportedLanguages(schemaName);
        boolean languagesConfigured = langs != null && !langs.isEmpty();

        Map<TenantConfigKeyEnum, TenantConfigStatusResponseDTO.ConfigEntry> configs = new LinkedHashMap<>();
        int configuredCount = 0;

        for (TenantConfigKeyEnum key : TenantConfigKeyEnum.values()) {
            boolean configured = key.getType() == TenantConfigKeyEnum.ConfigType.SPECIALIZED
                    ? languagesConfigured
                    : configuredKeys.contains(key.name());

            configs.put(key, TenantConfigStatusResponseDTO.ConfigEntry.builder()
                    .status(configured ? ConfigStatusEnum.CONFIGURED : ConfigStatusEnum.PENDING)
                    .build());

            if (configured) configuredCount++;
        }

        int total = TenantConfigKeyEnum.values().length;
        return TenantConfigStatusResponseDTO.builder()
                .tenantId(tenantId)
                .summary(TenantConfigStatusResponseDTO.Summary.builder()
                        .total(total)
                        .configured(configuredCount)
                        .pending(total - configuredCount)
                        .build())
                .configs(configs)
                .build();
    }

    private void handleSpecializedConfig(String schemaName, TenantConfigKeyEnum key, ConfigValueDTO dto,
            Integer currentUserId) {
        switch (key) {
            case SUPPORTED_LANGUAGES -> {
                if (!(dto instanceof LanguageListConfigDTO langDto)) {
                    throw new InvalidConfigValueException(
                            "Expected LanguageListConfigDTO for SUPPORTED_LANGUAGES, got "
                                    + dto.getClass().getSimpleName());
                }
                tenantSchemaRepository.setSupportedLanguages(schemaName, langDto.getLanguages(), currentUserId);
            }
            default -> throw new UnsupportedOperationException("No specialized handler for: " + key);
        }
    }


    @Override
    public LocationHierarchyResponseDTO getLocationHierarchy(Integer tenantId, String hierarchyType) {
        log.info("Fetching location hierarchy [id={}, hierarchyType={}]", tenantId, hierarchyType);
        validateNotSystemTenant(tenantId);

        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();

        try {
            RegionTypeEnum regionType = RegionTypeEnum.valueOf(hierarchyType.toUpperCase());
            LocationConfigDTO hierarchyConfig = tenantSchemaRepository.getLocationHierarchy(schemaName, regionType);
            
            if (hierarchyConfig == null || hierarchyConfig.getLocationHierarchy() == null) {
                throw new ResourceNotFoundException(
                        "Hierarchy configuration not found for type: " + hierarchyType + " in tenant [id=" + tenantId + "]");
            }

            log.info("Location hierarchy retrieved successfully [id={}, hierarchyType={}]", tenantId, hierarchyType);
            
            return LocationHierarchyResponseDTO.builder()
                    .hierarchyType(hierarchyType)
                    .levels(hierarchyConfig.getLocationHierarchy())
                    .build();
        } catch (IllegalArgumentException e) {
            log.error("Invalid hierarchy type [id={}, hierarchyType={}]", tenantId, hierarchyType, e);
            throw new IllegalArgumentException("Invalid hierarchy type: " + hierarchyType + ". Valid values: LGD, DEPARTMENT", e);
        }
    }

    @Override
    public List<LocationResponseDTO> getLocationChildren(Integer tenantId, String hierarchyType, Integer parentId) {
        log.info("Fetching location children [id={}, hierarchyType={}, parentId={}]", tenantId, hierarchyType, parentId);
        validateNotSystemTenant(tenantId);

        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();

        try {
            RegionTypeEnum regionType = RegionTypeEnum.valueOf(hierarchyType.toUpperCase());
            
            List<LocationResponseDTO> children;
            if (RegionTypeEnum.LGD.equals(regionType)) {
                children = tenantSchemaRepository.findLgdLocationsByParentId(schemaName, parentId);
            } else if (RegionTypeEnum.DEPARTMENT.equals(regionType)) {
                children = tenantSchemaRepository.findDepartmentLocationsByParentId(schemaName, parentId);
            } else {
                throw new IllegalArgumentException("Unknown hierarchy type: " + hierarchyType);
            }

            log.info("Location children retrieved successfully [id={}, hierarchyType={}, count={}]", 
                    tenantId, hierarchyType, children.size());
            
            return children;
        } catch (IllegalArgumentException e) {
            log.error("Invalid hierarchy type [id={}, hierarchyType={}]", tenantId, hierarchyType, e);
            throw new IllegalArgumentException("Invalid hierarchy type: " + hierarchyType + ". Valid values: LGD, DEPARTMENT", e);
        }
    }


    @Override
    public LocationHierarchyEditConstraintsResponseDTO getLocationHierarchyEditConstraints(
            Integer tenantId, String hierarchyType) {
        log.info("Fetching location hierarchy edit constraints [id={}, hierarchyType={}]", tenantId, hierarchyType);
        validateNotSystemTenant(tenantId);

        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        RegionTypeEnum regionType = resolveRegionType(hierarchyType);
        String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();
        long seededCount = tenantSchemaRepository.countSeededLocationData(schemaName, regionType);

        return LocationHierarchyEditConstraintsResponseDTO.builder()
                .hierarchyType(hierarchyType.toUpperCase())
                .structuralChangesAllowed(seededCount == 0)
                .seededRecordCount(seededCount)
                .build();
    }

    @Override
    @Transactional
    public LocationHierarchyResponseDTO updateLocationHierarchy(
            Integer tenantId, String hierarchyType, List<LocationLevelConfigDTO> levels) {
        log.info("Updating location hierarchy [id={}, hierarchyType={}]", tenantId, hierarchyType);
        validateNotSystemTenant(tenantId);

        if (levels == null || levels.isEmpty()) {
            throw new InvalidConfigValueException("Hierarchy levels cannot be null or empty");
        }

        TenantResponseDTO tenant = tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        if (levels.stream().anyMatch(Objects::isNull)
                || levels.stream().map(LocationLevelConfigDTO::getLevel).anyMatch(Objects::isNull)) {
            throw new InvalidConfigValueException("Each hierarchy level must be non-null and include a level number");
        }

        Set<Integer> levelNumbers = levels.stream()
                .map(LocationLevelConfigDTO::getLevel)
                .collect(Collectors.toSet());
        if (levelNumbers.size() < levels.size()) {
            throw new InvalidConfigValueException("Hierarchy level numbers must be unique");
        }

        RegionTypeEnum regionType = resolveRegionType(hierarchyType);
        String schemaName = "tenant_" + tenant.getStateCode().toLowerCase();
        Integer currentUserId = resolveCurrentUserId();

        LocationConfigDTO existing = tenantSchemaRepository.getLocationHierarchy(schemaName, regionType);
        List<LocationLevelConfigDTO> existingLevels =
                existing != null && existing.getLocationHierarchy() != null
                        ? existing.getLocationHierarchy()
                        : List.of();

        boolean isStructuralChange = isStructuralChange(existingLevels, levels);

        if (isStructuralChange) {
            tenantSchemaRepository.rewriteLocationHierarchyIfNoSeededData(schemaName, regionType, levels, currentUserId);
        } else {
            tenantSchemaRepository.updateLevelNames(schemaName, regionType, levels, currentUserId);
        }

        log.info("Location hierarchy updated successfully [id={}, hierarchyType={}, structuralChange={}]",
                tenantId, hierarchyType, isStructuralChange);

        return LocationHierarchyResponseDTO.builder()
                .hierarchyType(hierarchyType.toUpperCase())
                .levels(levels)
                .build();
    }

    /**
     * Returns true if the incoming level list differs structurally from the existing one.
     * A structural change means the number of levels or any level number differs.
     * Pure name changes within the same level numbers are not structural.
     */
    private boolean isStructuralChange(List<LocationLevelConfigDTO> existing, List<LocationLevelConfigDTO> incoming) {
        List<LocationLevelConfigDTO> safeExisting = existing.stream()
                .filter(e -> e != null && e.getLevel() != null)
                .collect(Collectors.toList());
        if (safeExisting.size() != incoming.size()) {
            return true;
        }
        Set<Integer> existingLevelNumbers = safeExisting.stream()
                .map(LocationLevelConfigDTO::getLevel)
                .collect(Collectors.toSet());
        Set<Integer> incomingLevelNumbers = incoming.stream()
                .map(LocationLevelConfigDTO::getLevel)
                .collect(Collectors.toSet());
        return !existingLevelNumbers.equals(incomingLevelNumbers);
    }

    private void validateNotSystemTenant(Integer tenantId) {
        if (tenantId != null && tenantId.equals(TenantConstants.SYSTEM_TENANT_ID)) {
            throw new IllegalArgumentException("Operation not permitted on the system tenant.");
        }
    }

    private RegionTypeEnum resolveRegionType(String hierarchyType) {
        try {
            return RegionTypeEnum.valueOf(hierarchyType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid hierarchy type: " + hierarchyType + ". Valid values: LGD, DEPARTMENT", e);
        }
    }

    @Override
    @Transactional
    public TenantConfigResponseDTO setTenantLogo(Integer tenantId, LogoSource source) {
        log.info("Setting tenant logo [id={}, source={}]", tenantId, source.getClass().getSimpleName());
        validateNotSystemTenant(tenantId);
        tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));

        String oldValue = tenantCommonRepository
                .findConfigByTenantAndKey(tenantId, TenantConfigKeyEnum.TENANT_LOGO.name())
                .map(cfg -> parseLogoValue(cfg.getConfigValue()))
                .orElse(null);

        String newValue = switch (source) {
            case LogoSource.FileSource fs -> {
                validateLogoFile(fs.file());
                String ext = resolveLogoExtension(fs.file().getContentType());
                String objectKey = "logos/" + tenantId + "/" + UUID.randomUUID() + "." + ext;
                final InputStream logoStream;
                try {
                    logoStream = fs.file().getInputStream();
                } catch (IOException e) {
                    throw new StorageException("Failed to open uploaded logo file", e);
                }
                try (InputStream stream = logoStream) {
                    yield objectStorageService.upload(objectKey, stream,
                            fs.file().getSize(), fs.file().getContentType());
                } catch (IOException e) {
                    throw new StorageException("Failed to read or close uploaded logo stream", e);
                }
            }
            case LogoSource.UrlSource us -> {
                validateLogoUrl(us.url());
                yield us.url();
            }
        };

        // Register S3 side-effect cleanup immediately after upload, before any subsequent call can
        // fail and orphan the uploaded object:
        //  - afterCommit: delete the old logo object (we own it; external URLs are skipped).
        //  - afterCompletion on rollback: delete the newly uploaded object to avoid orphaned storage.
        final String uploadedKey = (source instanceof LogoSource.FileSource) ? newValue : null;
        final String prevKey = (oldValue != null && !isExternalUrl(oldValue)) ? oldValue : null;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (prevKey != null) {
                    try {
                        log.info("Deleting previous logo object from storage [key={}]", prevKey);
                        objectStorageService.delete(prevKey);
                        log.info("Deleted previous logo object [key={}]", prevKey);
                    } catch (Exception e) {
                        log.warn("Failed to delete previous logo object [key={}]: {}", prevKey, e.getMessage());
                    }
                } else {
                    log.debug("No previous managed logo to delete");
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK && uploadedKey != null) {
                    try {
                        log.warn("Transaction rolled back — cleaning up uploaded logo [key={}]", uploadedKey);
                        objectStorageService.delete(uploadedKey);
                    } catch (Exception e) {
                        log.warn("Failed to clean up uploaded logo after rollback [key={}]: {}", uploadedKey, e.getMessage());
                    }
                }
            }
        });

        Integer currentUserId = resolveCurrentUserId();
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(new SimpleConfigValueDTO(newValue));
        } catch (JsonProcessingException e) {
            throw new InvalidConfigValueException("Failed to serialize logo value", e);
        }

        tenantCommonRepository
                .upsertConfig(tenantId, TenantConfigKeyEnum.TENANT_LOGO.name(), serialized, currentUserId)
                .orElseThrow(() -> new RuntimeException("Failed to upsert TENANT_LOGO config"));

        Map<TenantConfigKeyEnum, ConfigValueDTO> result = new HashMap<>();
        result.put(TenantConfigKeyEnum.TENANT_LOGO, new SimpleConfigValueDTO(newValue));

        return TenantConfigResponseDTO.builder().tenantId(tenantId).configs(result).build();
    }

    @Override
    public TenantLogoResult resolveTenantLogo(Integer tenantId) {
        validateNotSystemTenant(tenantId);
        tenantCommonRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant with tenantId " + tenantId + " does not exist"));
        String logoValue = tenantCommonRepository
                .findConfigByTenantAndKey(tenantId, TenantConfigKeyEnum.TENANT_LOGO.name())
                .map(cfg -> parseLogoValue(cfg.getConfigValue()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Logo not configured for tenant [id=" + tenantId + "]"));
        if (isExternalUrl(logoValue)) {
            return new TenantLogoResult.External(logoValue);
        }
        try (InputStream rawStream = objectStorageService.download(logoValue)) {
            byte[] bytes = rawStream.readAllBytes();
            return new TenantLogoResult.Managed(new ByteArrayInputStream(bytes), resolveLogoContentType(logoValue));
        } catch (IOException e) {
            throw new StorageException("Failed to read logo from storage [key=" + logoValue + "]", e);
        }
    }

    private static String resolveLogoContentType(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private void validateLogoUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Logo URL must use http or https scheme.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Logo URL must have a valid host.");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid logo URL: " + e.getMessage(), e);
        }
    }

    private void validateLogoFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Logo file must not be empty");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Logo file must not exceed 2 MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_LOGO_TYPES.containsKey(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported logo file type: " + contentType + ". Allowed: " + ALLOWED_LOGO_TYPES.keySet());
        }
    }

    private String resolveLogoExtension(String contentType) {
        String ext = ALLOWED_LOGO_TYPES.get(contentType);
        if (ext == null) {
            throw new IllegalArgumentException(
                    "Unsupported logo file type: " + contentType + ". Allowed: " + ALLOWED_LOGO_TYPES.keySet());
        }
        return ext;
    }

    private String parseLogoValue(String configJson) {
        try {
            return objectMapper.readValue(configJson, SimpleConfigValueDTO.class).getValue();
        } catch (JsonProcessingException e) {
            log.warn("[TenantManagementService] Failed to parse logo config JSON: {} | raw={}", e.getMessage(), configJson);
            return null;
        }
    }

    private boolean isExternalUrl(String value) {
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private List<String> fetchSystemSupportedChannels() {
        return systemManagementService.getSystemSupportedChannels();
    }

    private Integer resolveCurrentUserId() {
        String uuid = SecurityUtils.getCurrentUserUuid();
        log.debug("[TenantManagementService] Resolving user id for uuid={}", uuid);
        return tenantCommonRepository.findUserIdByUuid(uuid)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }

    private void setDefaultConfigs(TenantResponseDTO tenant, String schemaName, Integer currentUserId) {
        tenantSchemaRepository.setLocationHierarchy(
                schemaName, RegionTypeEnum.LGD, tenantDefaults.getLgdLocationHierarchy(), currentUserId);

        tenantSchemaRepository.setLocationHierarchy(
                schemaName, RegionTypeEnum.DEPARTMENT, tenantDefaults.getDeptLocationHierarchy(), currentUserId);

        try {
            ReasonListConfigDTO reasons = ReasonListConfigDTO.builder()
                    .reasons(tenantDefaults.getMeterChangeReasons())
                    .build();
            tenantCommonRepository.upsertConfig(tenant.getId(),
                    TenantConfigKeyEnum.METER_CHANGE_REASONS.name(),
                    objectMapper.writeValueAsString(reasons), currentUserId)
                    .orElseThrow(() -> new ConfigurationException(
                            "Failed to seed METER_CHANGE_REASONS for tenant [id=" + tenant.getId() + ", userId=" + currentUserId + "]"));

            ReasonListConfigDTO supplyOutageReasons = ReasonListConfigDTO.builder()
                    .reasons(tenantDefaults.getSupplyOutageReasons())
                    .build();
            tenantCommonRepository.upsertConfig(tenant.getId(),
                    TenantConfigKeyEnum.SUPPLY_OUTAGE_REASONS.name(),
                    objectMapper.writeValueAsString(supplyOutageReasons), currentUserId)
                    .orElseThrow(() -> new ConfigurationException(
                            "Failed to seed SUPPLY_OUTAGE_REASONS for tenant [id=" + tenant.getId() + ", userId=" + currentUserId + "]"));

            tenantCommonRepository.upsertConfig(tenant.getId(),
                    TenantConfigKeyEnum.LOCATION_CHECK_REQUIRED.name(),
                    objectMapper.writeValueAsString(new SimpleConfigValueDTO("NO")), currentUserId)
                    .orElseThrow(() -> new ConfigurationException(
                            "Failed to seed LOCATION_CHECK_REQUIRED for tenant [id=" + tenant.getId() + ", userId=" + currentUserId + "]"));
        } catch (JsonProcessingException e) {
            throw new InvalidConfigValueException("Failed to serialize default tenant configs", e);
        }

        log.info("Default configs seeded for tenant [id={}]", tenant.getId());
    }
}
