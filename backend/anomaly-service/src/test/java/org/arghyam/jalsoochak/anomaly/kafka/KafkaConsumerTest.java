package org.arghyam.jalsoochak.anomaly.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.anomaly.service.AnalyticsDimensionSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KafkaConsumerTest {

    private final AnalyticsDimensionSyncService sync = mock(AnalyticsDimensionSyncService.class);
    private final KafkaConsumer consumer = new KafkaConsumer(new ObjectMapper(), sync);

    @Test
    void routesUserEventsToTheDimensionSync() {
        consumer.consumeUserEvents("{\"eventType\":\"USER_UPDATED\",\"userId\":1,\"tenantId\":2}");

        verify(sync).upsertUser(any(JsonNode.class));
    }

    @Test
    void ignoresOtherUserEvents() {
        consumer.consumeUserEvents("{\"eventType\":\"USER_SCHEME_MAPPINGS_REPLACED\",\"userId\":1}");

        verify(sync, never()).upsertUser(any(JsonNode.class));
    }

    /**
     * Scheme dimensions are owned by analytics-service (SCHEME_DIMENSION_REPLACED). The old
     * SCHEME_UPDATED handler here wrote dim_scheme_table with a conflict target and a column the table
     * no longer has, so it only ever failed; it must not come back.
     */
    @Test
    void doesNotListenToSchemeServiceEvents() {
        boolean listensToSchemeTopic = Arrays.stream(KafkaConsumer.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(KafkaListener.class))
                .filter(a -> a != null)
                .flatMap(a -> Arrays.stream(a.topics()))
                .anyMatch("scheme-service-topic"::equals);

        assertThat(listensToSchemeTopic).isFalse();
        assertThat(Arrays.stream(AnalyticsDimensionSyncService.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("upsertScheme");
    }
}
