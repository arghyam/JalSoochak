package org.arghyam.jalsoochak.analytics.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class RedisNamespaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(RedisNamespaceInitializer.class);

    private final StringRedisTemplate redisTemplate;
    private final String appName;

    public RedisNamespaceInitializer(
            StringRedisTemplate redisTemplate,
            @Value("${spring.application.name}") String appName) {
        this.redisTemplate = redisTemplate;
        this.appName = appName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void writeServiceMetadata() {
        try {
            String now = Instant.now().toString();
            String metadataKey = appName + ":meta:service";
            redisTemplate.opsForHash().putAll(metadataKey, Map.of(
                    "name", appName,
                    "status", "UP",
                    "lastStartedAt", now
            ));
            redisTemplate.opsForValue().set(appName + ":meta:lastHeartbeat", now);
            log.info("Redis namespace initialized for service '{}'", appName);
        } catch (Exception ex) {
            log.warn("Unable to write service metadata to Redis for '{}'", appName, ex);
        }
    }
}
