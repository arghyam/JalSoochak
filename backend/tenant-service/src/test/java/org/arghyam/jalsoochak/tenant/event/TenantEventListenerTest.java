package org.arghyam.jalsoochak.tenant.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelNameDTO;
import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;


/**
 * Unit tests for {@link TenantEventListener}.
 *
 * <p>Verifies Redis caching/eviction and Kafka event publishing for all three
 * tenant lifecycle events. Redis failures are caught and must not prevent
 * Kafka publishing; Kafka failures are also caught independently.</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantEventListenerTest {

    @Mock
    private KafkaProducer kafkaProducer;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private TenantEventListener listener;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Test
    void handleTenantDeactivated_publishesTenantUpdatedEventType() {
        TenantResponseDTO tenant = TenantResponseDTO.builder()
                .id(1).stateCode("MP").name("Madhya Pradesh").status("ACTIVE").build();

        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        listener.handleTenantDeactivated(new TenantDeactivatedEvent(tenant));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        assertThat(captor.getValue().get("eventType")).isEqualTo("TENANT_UPDATED");
    }

    @Test
    void handleWaterNormUpdated_publishesCorrectPayload() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent(2, "TR", 55);

        listener.handleWaterNormUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("eventType")).isEqualTo("WATER_NORM_UPDATED");
        assertThat(payload.get("tenantId")).isEqualTo(2);
        assertThat(payload.get("stateCode")).isEqualTo("TR");
        assertThat(payload.get("waterNorm")).isEqualTo(55);
    }

    @Test
    void handleIncludedWorkStatusesUpdated_publishesCorrectPayload() {
        IncludedWorkStatusesUpdatedEvent event =
                new IncludedWorkStatusesUpdatedEvent(2, "TR", List.of(1, 4));

        listener.handleIncludedWorkStatusesUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("eventType")).isEqualTo("INCLUDED_WORK_STATUSES_UPDATED");
        assertThat(payload.get("tenantId")).isEqualTo(2);
        assertThat(payload.get("stateCode")).isEqualTo("TR");
        assertThat(payload.get("workStatuses")).isEqualTo(List.of(1, 4));
    }

    @Test
    void handleIncludedWorkStatusesUpdated_nationalTenantZero_publishes() {
        // tenant-0 (national default) uses the "NATIONAL" sentinel state code and must still publish.
        IncludedWorkStatusesUpdatedEvent event =
                new IncludedWorkStatusesUpdatedEvent(0, "NATIONAL", List.of(1));

        listener.handleIncludedWorkStatusesUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("tenantId")).isEqualTo(0);
        assertThat(payload.get("stateCode")).isEqualTo("NATIONAL");
        assertThat(payload.get("workStatuses")).isEqualTo(List.of(1));
    }

    @Test
    void handleIncludedWorkStatusesUpdated_skipsKafka_whenTenantIdIsNull() {
        IncludedWorkStatusesUpdatedEvent event =
                new IncludedWorkStatusesUpdatedEvent(null, "TR", List.of(4));

        listener.handleIncludedWorkStatusesUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleIncludedWorkStatusesUpdated_skipsKafka_whenWorkStatusesNullOrEmpty() {
        listener.handleIncludedWorkStatusesUpdated(
                new IncludedWorkStatusesUpdatedEvent(1, "TR", null));
        listener.handleIncludedWorkStatusesUpdated(
                new IncludedWorkStatusesUpdatedEvent(1, "TR", List.of()));

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleRegularityThresholdUpdated_publishesCorrectPayload() {
        RegularityThresholdUpdatedEvent event =
                new RegularityThresholdUpdatedEvent(2, "TR", 87.5);

        listener.handleRegularityThresholdUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("eventType")).isEqualTo("REGULARITY_THRESHOLD_UPDATED");
        assertThat(payload.get("tenantId")).isEqualTo(2);
        assertThat(payload.get("stateCode")).isEqualTo("TR");
        assertThat(payload.get("thresholdPercent")).isEqualTo(87.5);
    }

    @Test
    void handleRegularityThresholdUpdated_nationalTenantZero_publishes() {
        // tenant-0 (national default) uses the "NATIONAL" sentinel state code and must still publish.
        RegularityThresholdUpdatedEvent event =
                new RegularityThresholdUpdatedEvent(0, "NATIONAL", 90.0);

        listener.handleRegularityThresholdUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("tenantId")).isEqualTo(0);
        assertThat(payload.get("stateCode")).isEqualTo("NATIONAL");
        assertThat(payload.get("thresholdPercent")).isEqualTo(90.0);
    }

    @Test
    void handleRegularityThresholdUpdated_skipsKafka_whenTenantIdOrThresholdIsNull() {
        listener.handleRegularityThresholdUpdated(
                new RegularityThresholdUpdatedEvent(null, "TR", 90.0));
        listener.handleRegularityThresholdUpdated(
                new RegularityThresholdUpdatedEvent(1, "TR", null));

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleRegularityThresholdUpdated_kafkaFailureIsSwallowed() {
        doThrow(new RuntimeException("broker down"))
                .when(kafkaProducer).publishJson(anyString(), any());

        // A publish failure must not propagate out of an AFTER_COMMIT listener.
        listener.handleRegularityThresholdUpdated(
                new RegularityThresholdUpdatedEvent(2, "TR", 90.0));
    }

    @Test
    void handleLocationHierarchyUpdated_publishesCorrectPayload() {
        LocationLevelNameDTO name = LocationLevelNameDTO.builder().languageId(1).title("District").build();
        LocationLevelConfigDTO level = LocationLevelConfigDTO.builder()
                .level(2).levelName(List.of(name)).build();
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(3, "GJ", "LGD", List.of(level));

        listener.handleLocationHierarchyUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("eventType")).isEqualTo("TENANT_LOCATION_HIERARCHY_UPDATED");
        assertThat(payload.get("tenantId")).isEqualTo(3);
        assertThat(payload.get("stateCode")).isEqualTo("GJ");
        assertThat(payload.get("hierarchyType")).isEqualTo("LGD");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> levels = (List<Map<String, Object>>) payload.get("levels");
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0).get("level")).isEqualTo(2);
        assertThat(levels.get(0).get("name")).isEqualTo("District");
    }


    @Test
    void handleWaterSupplyThresholdUpdated_publishesCorrectPayload() {
        WaterSupplyThresholdUpdatedEvent event = new WaterSupplyThresholdUpdatedEvent(4, "MH", 20, 30);

        listener.handleWaterSupplyThresholdUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload.get("eventType")).isEqualTo("WATER_SUPPLY_THRESHOLD_UPDATED");
        assertThat(payload.get("tenantId")).isEqualTo(4);
        assertThat(payload.get("stateCode")).isEqualTo("MH");
        assertThat(payload.get("underSupplyThresholdPercent")).isEqualTo(20);
        assertThat(payload.get("overSupplyThresholdPercent")).isEqualTo(30);
    }

    @Test
    void handleWaterSupplyThresholdUpdated_skipsKafka_whenTenantIdIsNull() {
        WaterSupplyThresholdUpdatedEvent event = new WaterSupplyThresholdUpdatedEvent(null, "MH", 20, 30);

        listener.handleWaterSupplyThresholdUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleWaterSupplyThresholdUpdated_skipsKafka_whenStateCodeIsBlank() {
        WaterSupplyThresholdUpdatedEvent event = new WaterSupplyThresholdUpdatedEvent(5, "  ", 20, 30);

        listener.handleWaterSupplyThresholdUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private TenantResponseDTO tenant(int id, String stateCode, String name, String status) {
        return TenantResponseDTO.builder()
                .id(id)
                .stateCode(stateCode)
                .name(name)
                .status(status)
                .build();
    }

    private void stubRedisOps() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    // ── handleTenantCreated ───────────────────────────────────────────────────────

    @Test
    void handleTenantCreated_cachesInRedis_andPublishesToKafka() {
        stubRedisOps();
        TenantResponseDTO t = tenant(1, "mp", "Madhya Pradesh", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_mp");

        listener.handleTenantCreated(event);

        verify(hashOperations).putAll(eq("tenant-service:tenants:MP:profile"), argThat(map -> {
            Map<?, ?> m = (Map<?, ?>) map;
            return "MP".equals(m.get("stateCode")) && "tenant_mp".equals(m.get("schemaName"));
        }));
        verify(setOperations).add("tenant-service:tenants:index", "MP");
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), argThat(payload -> {
            if (!(payload instanceof Map)) return false;
            Map<?, ?> m = (Map<?, ?>) payload;
            return "TENANT_CREATED".equals(m.get("eventType")) && "mp".equals(m.get("stateCode"));
        }));
    }

    @Test
    void handleTenantCreated_uppercasesStateCodeInRedisKey() {
        stubRedisOps();
        TenantResponseDTO t = tenant(2, "up", "Uttar Pradesh", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_up");

        listener.handleTenantCreated(event);

        verify(hashOperations).putAll(eq("tenant-service:tenants:UP:profile"), any());
        verify(setOperations).add("tenant-service:tenants:index", "UP");
    }

    @Test
    void handleTenantCreated_skipsRedisCache_whenStateCodeIsNull() {
        TenantResponseDTO t = tenant(3, null, "NoState", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_unknown");

        listener.handleTenantCreated(event);

        verify(redisTemplate, never()).opsForHash();
        verify(redisTemplate, never()).opsForSet();
        // Kafka publish also skipped because stateCode is null
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleTenantCreated_skipsKafkaPublish_whenStateCodeIsBlank() {
        TenantResponseDTO t = tenant(4, "  ", "NoState", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_blank");

        listener.handleTenantCreated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
        verify(redisTemplate, never()).opsForHash();
        verify(redisTemplate, never()).opsForSet();
    }

    @Test
    void handleTenantCreated_stillPublishesToKafka_whenRedisFails() {
        when(redisTemplate.opsForHash()).thenThrow(new RuntimeException("Redis unavailable"));
        TenantResponseDTO t = tenant(5, "gj", "Gujarat", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_gj");

        listener.handleTenantCreated(event);

        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), any());
    }

    @Test
    void handleTenantCreated_stillCachesInRedis_whenKafkaFails() {
        stubRedisOps();
        doThrow(new RuntimeException("Kafka unavailable"))
                .when(kafkaProducer).publishJson(anyString(), any());
        TenantResponseDTO t = tenant(6, "hr", "Haryana", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_hr");

        listener.handleTenantCreated(event);

        verify(hashOperations).putAll(eq("tenant-service:tenants:HR:profile"), any());
        verify(setOperations).add("tenant-service:tenants:index", "HR");
    }

    // ── handleTenantUpdated ───────────────────────────────────────────────────────

    @Test
    void handleTenantUpdated_refreshesRedis_andPublishesToKafka() {
        stubRedisOps();
        TenantResponseDTO t = tenant(1, "mp", "Madhya Pradesh Updated", "ACTIVE");
        TenantUpdatedEvent event = new TenantUpdatedEvent(t);

        listener.handleTenantUpdated(event);

        verify(hashOperations).putAll(eq("tenant-service:tenants:MP:profile"), any());
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), argThat(payload -> {
            if (!(payload instanceof Map)) return false;
            Map<?, ?> m = (Map<?, ?>) payload;
            return "TENANT_UPDATED".equals(m.get("eventType"));
        }));
    }

    @Test
    void handleTenantUpdated_skipsRedisAndKafka_whenStateCodeIsNull() {
        TenantResponseDTO t = tenant(2, null, "NullState", "ACTIVE");
        TenantUpdatedEvent event = new TenantUpdatedEvent(t);

        listener.handleTenantUpdated(event);

        verify(redisTemplate, never()).opsForHash();
        verify(redisTemplate, never()).opsForSet();
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleTenantUpdated_stillPublishesToKafka_whenRedisFails() {
        when(redisTemplate.opsForHash()).thenThrow(new RuntimeException("Redis down"));
        TenantResponseDTO t = tenant(3, "rj", "Rajasthan", "ACTIVE");
        TenantUpdatedEvent event = new TenantUpdatedEvent(t);

        listener.handleTenantUpdated(event);

        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), any());
    }

    // ── handleTenantDeactivated ───────────────────────────────────────────────────

    @Test
    void handleTenantDeactivated_evictsFromRedis_andPublishesToKafka() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        TenantResponseDTO t = tenant(1, "mp", "Madhya Pradesh", "INACTIVE");
        TenantDeactivatedEvent event = new TenantDeactivatedEvent(t);

        listener.handleTenantDeactivated(event);

        verify(redisTemplate).delete("tenant-service:tenants:MP:profile");
        verify(setOperations).remove("tenant-service:tenants:index", "MP");
        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), argThat(payload -> {
            if (!(payload instanceof Map)) return false;
            Map<?, ?> m = (Map<?, ?>) payload;
            return "TENANT_UPDATED".equals(m.get("eventType"));
        }));
    }

    @Test
    void handleTenantDeactivated_skipsEviction_whenStateCodeIsBlank() {
        TenantResponseDTO t = tenant(2, "", "EmptyState", "INACTIVE");
        TenantDeactivatedEvent event = new TenantDeactivatedEvent(t);

        listener.handleTenantDeactivated(event);

        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).opsForSet();
        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleTenantDeactivated_stillPublishesToKafka_whenRedisFails() {
        when(redisTemplate.opsForSet()).thenThrow(new RuntimeException("Redis gone"));
        TenantResponseDTO t = tenant(3, "pb", "Punjab", "INACTIVE");
        TenantDeactivatedEvent event = new TenantDeactivatedEvent(t);

        listener.handleTenantDeactivated(event);

        verify(kafkaProducer).publishJson(eq("tenant-service-topic"), any());
    }

    // ── Kafka publish guards ──────────────────────────────────────────────────────

    @Test
    void publishTenantEvent_skipsKafka_whenNameIsBlank() {
        stubRedisOps();
        TenantResponseDTO t = tenant(10, "mh", "  ", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_mh");

        listener.handleTenantCreated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void publishTenantEvent_skipsKafka_whenStatusIsUnrecognized() {
        stubRedisOps();
        TenantResponseDTO t = tenant(11, "tn", "Tamil Nadu", "UNKNOWN_STATUS");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_tn");

        listener.handleTenantCreated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleWaterNormUpdated_skipsKafka_whenTenantIdIsNull() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent(null, "MP", 55);

        listener.handleWaterNormUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleWaterNormUpdated_skipsKafka_whenStateCodeIsBlank() {
        WaterNormUpdatedEvent event = new WaterNormUpdatedEvent(1, "  ", 55);

        listener.handleWaterNormUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleLocationHierarchyUpdated_skipsKafka_whenTenantIdIsNull() {
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(null, "MP", "LGD", List.of());

        listener.handleLocationHierarchyUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleLocationHierarchyUpdated_skipsKafka_whenStateCodeIsBlank() {
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(1, "  ", "LGD", List.of());

        listener.handleLocationHierarchyUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleLocationHierarchyUpdated_skipsKafka_whenHierarchyTypeIsNull() {
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(1, "MP", null, List.of());

        listener.handleLocationHierarchyUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleLocationHierarchyUpdated_skipsKafka_whenHierarchyTypeIsBlank() {
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(1, "MP", "  ", List.of());

        listener.handleLocationHierarchyUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleLocationHierarchyUpdated_skipsKafka_whenLevelsIsNull() {
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(1, "MP", "LGD", null);

        listener.handleLocationHierarchyUpdated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void handleLocationHierarchyUpdated_filtersLevelsWithNullLevel() {
        LocationLevelNameDTO name = LocationLevelNameDTO.builder().languageId(1).title("Block").build();
        org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO levelWithNull =
                org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO.builder()
                        .level(null).levelName(List.of(name)).build();
        org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO validLevel =
                org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO.builder()
                        .level(1).levelName(List.of(name)).build();
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(5, "RJ", "LGD", List.of(levelWithNull, validLevel));

        listener.handleLocationHierarchyUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> levels = (List<Map<String, Object>>) captor.getValue().get("levels");
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0).get("level")).isEqualTo(1);
    }

    @Test
    void handleLocationHierarchyUpdated_usesEmptyString_whenLevelNamesIsEmpty() {
        org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO level =
                org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO.builder()
                        .level(1).levelName(List.of()).build();
        TenantLocationHierarchyUpdatedEvent event =
                new TenantLocationHierarchyUpdatedEvent(6, "BR", "LGD", List.of(level));

        listener.handleLocationHierarchyUpdated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> levels = (List<Map<String, Object>>) captor.getValue().get("levels");
        assertThat(levels.get(0).get("name")).isEqualTo("");
    }

    @Test
    void publishTenantEvent_skipsKafka_whenStatusIsNull() {
        stubRedisOps();
        TenantResponseDTO t = tenant(20, "od", "Odisha", null);
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_od");

        listener.handleTenantCreated(event);

        verify(kafkaProducer, never()).publishJson(anyString(), any());
    }

    @Test
    void publishTenantEvent_includesCorrectStatusCode_forActiveStatus() {
        stubRedisOps();
        TenantResponseDTO t = tenant(12, "ka", "Karnataka", "ACTIVE");
        TenantCreatedEvent event = new TenantCreatedEvent(t, "tenant_ka");

        listener.handleTenantCreated(event);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).publishJson(anyString(), captor.capture());
        // ACTIVE = code 3
        assertThat(captor.getValue()).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) captor.getValue();
        assertThat(payload.get("status")).isEqualTo(3);
    }
}