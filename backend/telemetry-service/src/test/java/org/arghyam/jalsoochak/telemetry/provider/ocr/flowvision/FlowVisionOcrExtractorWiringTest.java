package org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingExtractor;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderRegistry;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsRetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenants with no {@code ocr_*} override are served by whichever {@link MeterReadingExtractor} the
 * neutral services receive when they inject the port by type. Every OCR provider is a bean of that type,
 * so a second provider would make that injection ambiguous and stop the context booting, or hand those
 * tenants to the wrong provider, unless the built-in one is marked primary.
 */
@DisplayName("OCR provider wiring — the built-in extractor answers a by-type injection of the port")
class FlowVisionOcrExtractorWiringTest {

    /** A second provider, registered the way a new AI backend would be. */
    static class OtherProviderExtractor implements MeterReadingExtractor {
        @Override
        public String providerId() {
            return "vision-x";
        }

        @Override
        public OcrReadingResult extractReading(String imageUrl, OcrProviderSettings settings) {
            return null;
        }

        @Override
        public OcrReadingResult extractReadingOrThrow(String imageUrl, OcrProviderSettings settings) {
            return null;
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(RestTemplate.class)
            .withBean(FlowVisionOcrExtractor.class)
            .withBean(OtherProviderExtractor.class)
            .withBean(OcrProviderRegistry.class)
            .withBean(RetryRegistry.class, RetryRegistry::ofDefaults)
            .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
            .withBean(BulkheadRegistry.class, BulkheadRegistry::ofDefaults)
            .withBean(OcrReadingsRetryService.class)
            .withPropertyValues("ocr.url=https://ocr.example/extract");

    @Test
    void resolvesThePortToTheBuiltInExtractorAlongsideASecondProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(MeterReadingExtractor.class).hasSize(2);
            assertThat(context.getBean(MeterReadingExtractor.class)).isInstanceOf(FlowVisionOcrExtractor.class);
        });
    }

    @Test
    void givesTheRetryServiceTheBuiltInExtractorAsItsDefault() {
        contextRunner.run(context -> {
            OcrReadingsRetryService retryService = context.getBean(OcrReadingsRetryService.class);
            assertThat(ReflectionTestUtils.getField(retryService, "defaultOcrExtractor"))
                    .isSameAs(context.getBean(FlowVisionOcrExtractor.class));
        });
    }
}
