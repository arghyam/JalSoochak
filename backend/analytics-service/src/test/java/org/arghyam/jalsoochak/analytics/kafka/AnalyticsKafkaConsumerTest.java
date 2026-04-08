package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterSupplyThresholdUpdatedEvent;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import org.arghyam.jalsoochak.analytics.service.FactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.arghyam.jalsoochak.analytics.dto.event.TenantEvent;

@ExtendWith(MockitoExtension.class)
class AnalyticsKafkaConsumerTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DimensionService dimensionService;

    @Mock
    private FactService factService;

    @InjectMocks
    private AnalyticsKafkaConsumer consumer;

    @Test
    void consumeTenantEvents_waterNormUpdated_routesToUpdateWaterNorm() throws Exception {
        String message = """
                {"eventType":"WATER_NORM_UPDATED","tenantId":1,"stateCode":"MP","waterNorm":70}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<WaterNormUpdatedEvent> captor = ArgumentCaptor.forClass(WaterNormUpdatedEvent.class);
        verify(dimensionService).updateWaterNorm(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(1);
        assertThat(captor.getValue().getWaterNorm()).isEqualTo(70);
        assertThat(captor.getValue().getStateCode()).isEqualTo("MP");
    }

    @Test
    void consumeUserEvents_userCreated_routesToUpsertUser() throws Exception {
        String message = """
                {"eventType":"USER_CREATED","userId":42,"tenantId":3,"email":"admin@state.gov","userType":2,"uuid":"11111111-1111-1111-1111-111111111111","status":1,"title":"First Last"}
                """;

        consumer.consumeUserEvents(message);

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(dimensionService).upsertUser(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("USER_CREATED");
        assertThat(captor.getValue().getUserId()).isEqualTo(42);
        assertThat(captor.getValue().getTenantId()).isEqualTo(3);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
        assertThat(captor.getValue().getTitle()).isEqualTo("First Last");
    }

    @Test
    void consumeUserEvents_userUpdated_routesToUpsertUser() throws Exception {
        String message = """
                {"eventType":"USER_UPDATED","userId":42,"tenantId":3,"email":"admin@state.gov","userType":2,"uuid":"11111111-1111-1111-1111-111111111111","status":0}
                """;

        consumer.consumeUserEvents(message);

        ArgumentCaptor<UserEvent> captor = ArgumentCaptor.forClass(UserEvent.class);
        verify(dimensionService).upsertUser(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("USER_UPDATED");
        assertThat(captor.getValue().getStatus()).isEqualTo(0);
        assertThat(captor.getValue().getTitle()).isNull();
    }

    @Test
    void consumeUserEvents_unknownEventType_isIgnored() throws Exception {
        String message = """
                {"eventType":"USER_DELETED","userId":42}
                """;

        consumer.consumeUserEvents(message);

        verifyNoInteractions(dimensionService);
        verifyNoInteractions(factService);
    }

    @Test
    void consumeTenantEvents_tenantUpdated_routesToUpsertTenant() throws Exception {
        String message = """
                {"eventType":"TENANT_UPDATED","tenantId":5,"stateCode":"RJ","title":"Rajasthan","status":1}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<TenantEvent> captor = ArgumentCaptor.forClass(TenantEvent.class);
        verify(dimensionService).upsertTenant(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(5);
        assertThat(captor.getValue().getStateCode()).isEqualTo("RJ");
        assertThat(captor.getValue().getTitle()).isEqualTo("Rajasthan");
    }

    @Test
    void consumeTenantEvents_waterSupplyThresholdUpdated_routesToUpdateWaterSupplyThreshold() throws Exception {
        String message = """
                {"eventType":"WATER_SUPPLY_THRESHOLD_UPDATED","tenantId":3,"stateCode":"MH","underSupplyThresholdPercent":20,"overSupplyThresholdPercent":30}
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<WaterSupplyThresholdUpdatedEvent> captor =
                ArgumentCaptor.forClass(WaterSupplyThresholdUpdatedEvent.class);
        verify(dimensionService).updateWaterSupplyThreshold(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(3);
        assertThat(captor.getValue().getStateCode()).isEqualTo("MH");
        assertThat(captor.getValue().getUnderSupplyThresholdPercent()).isEqualTo(20);
        assertThat(captor.getValue().getOverSupplyThresholdPercent()).isEqualTo(30);
    }

    @Test
    void consumeTenantEvents_tenantLocationHierarchyUpdated_routesToUpdateLocationHierarchyNames() throws Exception {
        String message = """
                {
                  "eventType":"TENANT_LOCATION_HIERARCHY_UPDATED",
                  "tenantId":2,"stateCode":"GJ","hierarchyType":"LGD",
                  "levels":[{"level":1,"name":"State"},{"level":2,"name":"District"}]
                }
                """;

        consumer.consumeTenantEvents(message);

        ArgumentCaptor<TenantLocationHierarchyUpdatedEvent> captor =
                ArgumentCaptor.forClass(TenantLocationHierarchyUpdatedEvent.class);
        verify(dimensionService).updateLocationHierarchyNames(captor.capture());
        TenantLocationHierarchyUpdatedEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(2);
        assertThat(event.getHierarchyType()).isEqualTo("LGD");
        assertThat(event.getLevels()).hasSize(2);
        assertThat(event.getLevels().get(0).getName()).isEqualTo("State");
    }
}
