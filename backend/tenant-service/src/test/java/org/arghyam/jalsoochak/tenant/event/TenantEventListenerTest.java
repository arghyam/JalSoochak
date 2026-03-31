package org.arghyam.jalsoochak.tenant.event;

import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private TenantEventListener listener;

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
            return "TENANT_DEACTIVATED".equals(m.get("eventType"));
        }));
    }

    @Test
    void handleTenantDeactivated_skipsEviction_whenStateCodeIsBlank() {
        TenantResponseDTO t = tenant(2, "", "EmptyState", "INACTIVE");
        TenantDeactivatedEvent event = new TenantDeactivatedEvent(t);

        listener.handleTenantDeactivated(event);

        verify(redisTemplate, never()).delete(anyString());
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