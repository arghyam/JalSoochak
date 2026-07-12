package org.arghyam.jalsoochak.telemetry.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import org.arghyam.jalsoochak.telemetry.service.FlowVisionTransientFailures;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowVisionResilienceConfig {

    private static final String READINGS_INSTANCE_NAME = "flowvisionReadings";

    @Bean
    RetryConfigCustomizer flowVisionReadingsRetryExceptions() {
        return RetryConfigCustomizer.of(
                READINGS_INSTANCE_NAME,
                builder -> builder.retryExceptions(FlowVisionTransientFailures.retriableExceptions())
        );
    }

    @Bean
    CircuitBreakerConfigCustomizer flowVisionReadingsCircuitBreakerExceptions() {
        return CircuitBreakerConfigCustomizer.of(
                READINGS_INSTANCE_NAME,
                builder -> builder.recordExceptions(FlowVisionTransientFailures.retriableExceptions())
        );
    }
}
