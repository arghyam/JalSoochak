package org.arghyam.jalsoochak.tenant.event;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantEventListenerTest {

    @Mock
    private KafkaProducer kafkaProducer;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private TenantEventListener listener;

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
}
