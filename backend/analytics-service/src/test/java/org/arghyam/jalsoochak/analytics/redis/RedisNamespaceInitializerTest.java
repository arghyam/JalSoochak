package org.arghyam.jalsoochak.analytics.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisNamespaceInitializerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void writeServiceMetadata_writesHashAndHeartbeat() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        new RedisNamespaceInitializer(redisTemplate, "analytics-service").writeServiceMetadata();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("analytics-service:meta:service"), mapCaptor.capture());
        Map<Object, Object> meta = mapCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(meta)
                .containsEntry("name", "analytics-service")
                .containsEntry("status", "UP")
                .containsKey("lastStartedAt");
        verify(valueOperations).set(eq("analytics-service:meta:lastHeartbeat"), anyString());
    }

    @Test
    void writeServiceMetadata_doesNotPropagateRedisErrors() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        doThrow(new RuntimeException("redis unavailable")).when(hashOperations).putAll(anyString(), anyMap());

        assertThatCode(() -> new RedisNamespaceInitializer(redisTemplate, "analytics-service").writeServiceMetadata())
                .doesNotThrowAnyException();
    }
}
