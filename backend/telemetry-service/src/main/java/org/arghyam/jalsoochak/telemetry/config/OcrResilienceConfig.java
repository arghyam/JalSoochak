package org.arghyam.jalsoochak.telemetry.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import org.arghyam.jalsoochak.telemetry.service.OcrTransientFailures;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OcrResilienceConfig {

    private static final String READINGS_INSTANCE_NAME = "flowvisionReadings";

    @Bean
    RetryConfigCustomizer ocrReadingsRetryExceptions() {
        return RetryConfigCustomizer.of(
                READINGS_INSTANCE_NAME,
                builder -> builder.retryExceptions(OcrTransientFailures.retriableExceptions())
        );
    }

    @Bean
    CircuitBreakerConfigCustomizer ocrReadingsCircuitBreakerExceptions() {
        return CircuitBreakerConfigCustomizer.of(
                READINGS_INSTANCE_NAME,
                builder -> builder.recordExceptions(OcrTransientFailures.retriableExceptions())
        );
    }
}
