package org.arghyam.jalsoochak.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gateway must read its Redis connection settings from the same environment
 * variables every other service uses (REDIS_HOST / REDIS_PORT / REDIS_PASSWORD /
 * REDIS_DATABASE). Diverging from that convention means a deployment configures
 * Redis for every service except this one, and the gateway silently falls back to
 * localhost — failing the Redis health indicator and the rate limiter alike.
 */
class RedisConnectionPropertiesTest {

    @Test
    void readsRedisConnectionFromSharedEnvironmentVariables() throws IOException {
        StandardEnvironment environment = environmentWith(Map.of(
                "REDIS_HOST", "redis.internal",
                "REDIS_PORT", "6380",
                "REDIS_PASSWORD", "s3cret",
                "REDIS_DATABASE", "3"));

        assertEquals("redis.internal", environment.getProperty("spring.data.redis.host"));
        assertEquals(6380, environment.getProperty("spring.data.redis.port", Integer.class));
        assertEquals("s3cret", environment.getProperty("spring.data.redis.password"));
        assertEquals(3, environment.getProperty("spring.data.redis.database", Integer.class));
    }

    @Test
    void fallsBackToLocalDefaultsWhenUnset() throws IOException {
        StandardEnvironment environment = environmentWith(Map.of());

        assertEquals("localhost", environment.getProperty("spring.data.redis.host"));
        assertEquals(6379, environment.getProperty("spring.data.redis.port", Integer.class));
        assertEquals("", environment.getProperty("spring.data.redis.password"));
        assertEquals(0, environment.getProperty("spring.data.redis.database", Integer.class));
    }

    @Test
    void boundsRedisCallsWithATimeout() throws IOException {
        assertEquals("5s", environmentWith(Map.of("REDIS_TIMEOUT", "5s"))
                .getProperty("spring.data.redis.timeout"));
        assertEquals("2s", environmentWith(Map.of())
                .getProperty("spring.data.redis.timeout"));
    }

    private static StandardEnvironment environmentWith(Map<String, Object> systemEnvironment) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        // JVM system properties outrank the environment, so a stray -DREDIS_HOST on the Maven
        // command line would decide these assertions instead of the map under test.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
