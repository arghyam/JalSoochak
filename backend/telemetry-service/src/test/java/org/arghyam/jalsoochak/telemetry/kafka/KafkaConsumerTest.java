package org.arghyam.jalsoochak.telemetry.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerTest {

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private KafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaConsumer(new ObjectMapper(), tenantConfigRepository);
    }

    @Test
    void consumeTenantServiceEventInvalidatesTenantConfigCache() {
        consumer.consumeTenantServiceEvent("""
                {"eventType":"TENANT_CONFIG_UPDATED","tenantId":17,"stateCode":"AS","configKeys":["TENANT_SUPPORTED_CHANNELS"]}
                """);

        verify(tenantConfigRepository).invalidateTenantConfigCache(17);
    }

    @Test
    void consumeTenantServiceEventIgnoresOtherEvents() {
        consumer.consumeTenantServiceEvent("""
                {"eventType":"TENANT_UPDATED","tenantId":17}
                """);

        verify(tenantConfigRepository, never()).invalidateTenantConfigCache(17);
    }
}
