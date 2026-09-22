package org.arghyam.jalsoochak.message.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PER-TENANT-PROVIDERS: the feature flag has to switch the listener off as well as the resolution,
 * or {@code notification.per-tenant-providers.enabled=false} is not the rollback it is documented
 * to be — every replica would still subscribe to {@code tenant-service-topic} to evict caches that
 * are permanently empty, and leave an abandoned consumer group behind on every restart.
 */
@DisplayName("TenantConfigUpdatedListener feature-flag condition")
class TenantConfigUpdatedListenerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("the listener is registered when the flag is on")
    void flagOnRegistersTheListener() {
        contextRunner
                .withPropertyValues("notification.per-tenant-providers.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TenantConfigUpdatedListener.class));
    }

    @Test
    @DisplayName("the listener is not registered when the flag is off")
    void flagOffLeavesTheListenerOut() {
        contextRunner
                .withPropertyValues("notification.per-tenant-providers.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TenantConfigUpdatedListener.class));
    }

    @Test
    @DisplayName("the listener is not registered when the flag is absent, which is the default")
    void flagAbsentLeavesTheListenerOut() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(TenantConfigUpdatedListener.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TenantConfigUpdatedListener.class)
    static class TestConfig {

        @Bean
        TenantChannelProviders tenantChannelProviders() {
            return mock(TenantChannelProviders.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
