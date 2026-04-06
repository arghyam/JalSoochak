package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
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
