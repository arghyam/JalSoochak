package org.arghyam.jalsoochak.tenant.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelNameDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class TenantEventListener {

    private static final String TENANT_TOPIC = "tenant-service-topic";
    private static final String REDIS_TENANT_KEY_PREFIX = "tenant-service:tenants:";
    private static final String REDIS_PROFILE_SUFFIX = ":profile";
    private static final String REDIS_TENANT_INDEX_KEY = "tenant-service:tenants:index";

    private final KafkaProducer kafkaProducer;
    private final StringRedisTemplate redisTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTenantCreated(TenantCreatedEvent event) {
        log.info("Handling TenantCreatedEvent after commit [id={}]", event.getTenant().getId());
        try {
            cacheTenantInRedis(event.getTenant(), event.getSchemaName());
        } catch (Exception e) {
            log.error("Failed to cache tenant in Redis after create [id={}]", event.getTenant().getId(), e);
        }
        try {
            publishTenantEvent(event.getTenant(), "TENANT_CREATED");
        } catch (Exception e) {
            log.error("Failed to publish TENANT_CREATED event [id={}]", event.getTenant().getId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTenantUpdated(TenantUpdatedEvent event) {
        log.info("Handling TenantUpdatedEvent after commit [id={}]", event.getTenant().getId());
        try {
            refreshTenantInRedis(event.getTenant());
        } catch (Exception e) {
            log.error("Failed to refresh tenant in Redis after update [id={}]", event.getTenant().getId(), e);
        }
        try {
            publishTenantEvent(event.getTenant(), "TENANT_UPDATED");
        } catch (Exception e) {
            log.error("Failed to publish TENANT_UPDATED event [id={}]", event.getTenant().getId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTenantDeactivated(TenantDeactivatedEvent event) {
        log.info("Handling TenantDeactivatedEvent after commit [id={}]", event.getTenant().getId());
        try {
            evictTenantFromRedis(event.getTenant());
        } catch (Exception e) {
            log.error("Failed to evict tenant from Redis after deactivate [id={}]", event.getTenant().getId(), e);
        }
        try {
            publishTenantEvent(event.getTenant(), "TENANT_UPDATED");
        } catch (Exception e) {
            log.error("Failed to publish TENANT_UPDATED event [id={}]", event.getTenant().getId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWaterNormUpdated(WaterNormUpdatedEvent event) {
        log.info("Handling WaterNormUpdatedEvent after commit [tenantId={}]", event.getTenantId());
        if (event.getTenantId() == null || event.getStateCode() == null || event.getStateCode().isBlank()) {
            log.warn("Cannot publish WATER_NORM_UPDATED event: tenantId or stateCode is null/blank [tenantId={}]", event.getTenantId());
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "eventType", "WATER_NORM_UPDATED",
                    "tenantId",  event.getTenantId(),
                    "stateCode", event.getStateCode(),
                    "waterNorm", event.getWaterNorm());
            kafkaProducer.publishJson(TENANT_TOPIC, payload);
            log.info("Published WATER_NORM_UPDATED event [tenantId={}]", event.getTenantId());
        } catch (Exception e) {
            log.error("Failed to publish WATER_NORM_UPDATED event [tenantId={}]", event.getTenantId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleLocationHierarchyUpdated(TenantLocationHierarchyUpdatedEvent event) {
        log.info("Handling TenantLocationHierarchyUpdatedEvent after commit [tenantId={}]", event.getTenantId());
        if (event.getTenantId() == null || event.getStateCode() == null || event.getStateCode().isBlank()) {
            log.warn("Cannot publish TENANT_LOCATION_HIERARCHY_UPDATED event: tenantId or stateCode is null/blank [tenantId={}]", event.getTenantId());
            return;
        }
        if (event.getLevels() == null) {
            log.warn("Cannot publish TENANT_LOCATION_HIERARCHY_UPDATED event: levels is null [tenantId={}]", event.getTenantId());
            return;
        }
        try {
            List<Map<String, Object>> levelList = event.getLevels().stream()
                    .map(l -> {
                        List<LocationLevelNameDTO> names = l.getLevelName();
                        String canonicalName = (names != null && !names.isEmpty())
                                ? names.get(0).getTitle()
                                : null;
                        return Map.<String, Object>of("level", l.getLevel(), "name", canonicalName != null ? canonicalName : "");
                    })
                    .toList();
            Map<String, Object> payload = Map.of(
                    "eventType",     "TENANT_LOCATION_HIERARCHY_UPDATED",
                    "tenantId",      event.getTenantId(),
                    "stateCode",     event.getStateCode(),
                    "hierarchyType", event.getHierarchyType(),
                    "levels",        levelList);
            kafkaProducer.publishJson(TENANT_TOPIC, payload);
            log.info("Published TENANT_LOCATION_HIERARCHY_UPDATED event [tenantId={}]", event.getTenantId());
        } catch (Exception e) {
            log.error("Failed to publish TENANT_LOCATION_HIERARCHY_UPDATED event [tenantId={}]", event.getTenantId(), e);
        }
    }

    private void publishTenantEvent(TenantResponseDTO tenant, String eventType) {
        String stateCode = tenant.getStateCode();
        if (stateCode == null || stateCode.isBlank()) {
            log.error("Cannot publish {} event: stateCode is null or blank [id={}]", eventType, tenant.getId());
            return;
        }
        String title = tenant.getName();
        if (title == null || title.isBlank()) {
            log.error("Cannot publish {} event: name is null or blank [id={}]", eventType, tenant.getId());
            return;
        }
        String rawStatus = tenant.getStatus();
        int statusInt;
        try {
            statusInt = TenantStatusEnum.valueOf(rawStatus).getCode();
        } catch (NullPointerException | IllegalArgumentException ex) {
            log.error("Cannot publish {} event: unrecognized or null status '{}' [id={}]",
                    eventType, rawStatus, tenant.getId());
            return;
        }
        Map<String, Object> event = Map.of(
                "eventType", eventType,
                "tenantId", tenant.getId(),
                "stateCode", stateCode,
                "title", title,
                "countryCode", "IN",
                "status", statusInt);

        kafkaProducer.publishJson(TENANT_TOPIC, event);
        log.info("Published {} event for tenant [id={}]", eventType, tenant.getId());
    }

    private void cacheTenantInRedis(TenantResponseDTO tenant, String schemaName) {
        String stateCode = tenant.getStateCode();
        if (stateCode == null || stateCode.isBlank()) {
            log.error("Cannot cache tenant in Redis: stateCode is null or blank [id={}]", tenant.getId());
            return;
        }
        String tenantStateCode = stateCode.toUpperCase();
        String tenantKey = REDIS_TENANT_KEY_PREFIX + tenantStateCode + REDIS_PROFILE_SUFFIX;

        Map<String, String> tenantPayload = new HashMap<>();
        tenantPayload.put("id", String.valueOf(tenant.getId()));
        tenantPayload.put("stateCode", tenantStateCode);
        tenantPayload.put("name", tenant.getName());
        tenantPayload.put("status", tenant.getStatus());
        tenantPayload.put("schemaName", schemaName);

        redisTemplate.opsForHash().putAll(tenantKey, tenantPayload);
        redisTemplate.opsForSet().add(REDIS_TENANT_INDEX_KEY, tenantStateCode);
        log.info("Tenant cached in Redis under key: {}", tenantKey);
    }

    private void refreshTenantInRedis(TenantResponseDTO tenant) {
        String stateCode = tenant.getStateCode();
        if (stateCode == null || stateCode.isBlank()) {
            log.error("Cannot refresh tenant in Redis: stateCode is null or blank [id={}]", tenant.getId());
            return;
        }
        String schemaName = "tenant_" + stateCode.trim().toLowerCase();
        cacheTenantInRedis(tenant, schemaName);
    }

    private void evictTenantFromRedis(TenantResponseDTO tenant) {
        String stateCode = tenant.getStateCode();
        if (stateCode == null || stateCode.isBlank()) {
            log.error("Cannot evict tenant from Redis: stateCode is null or blank [id={}]", tenant.getId());
            return;
        }
        String tenantStateCode = stateCode.toUpperCase();
        String tenantKey = REDIS_TENANT_KEY_PREFIX + tenantStateCode + REDIS_PROFILE_SUFFIX;

        redisTemplate.delete(tenantKey);
        redisTemplate.opsForSet().remove(REDIS_TENANT_INDEX_KEY, tenantStateCode);
        log.info("Tenant evicted from Redis cache under key: {}", tenantKey);
    }
}
