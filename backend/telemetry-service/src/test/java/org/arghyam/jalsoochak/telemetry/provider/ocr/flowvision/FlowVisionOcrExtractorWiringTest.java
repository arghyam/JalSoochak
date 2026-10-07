package org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.MeterReadingExtractor;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderRegistry;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderResolver;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderSettings;
import org.arghyam.jalsoochak.telemetry.service.OcrReadingsRetryService;
import org.arghyam.jalsoochak.telemetry.service.capture.ImageReadingCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every OCR provider is a bean of the {@link MeterReadingExtractor} type, and none is primary. That only
 * boots because nothing injects a single extractor: each photo's provider is picked by channel through
 * {@link OcrProviderRegistry}. A by-type injection added later would fail here rather than at deploy.
 */
@DisplayName("OCR provider wiring — providers are picked by channel, never injected one by one")
class FlowVisionOcrExtractorWiringTest {

    /** A provider for another channel, registered the way a new AI backend would be. */
    static class ElmProviderExtractor implements MeterReadingExtractor {
        @Override
        public String providerId() {
            return "elm-vision";
        }

        @Override
        public ReadingChannel channel() {
            return ReadingChannel.ELM;
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
            .withBean(ElmProviderExtractor.class)
            .withBean(OcrProviderRegistry.class)
            .withBean(RetryRegistry.class, RetryRegistry::ofDefaults)
            .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
            .withBean(BulkheadRegistry.class, BulkheadRegistry::ofDefaults)
            .withBean(OcrReadingsRetryService.class)
            .withBean(TelemetryTenantRepository.class, () -> Mockito.mock(TelemetryTenantRepository.class))
            .withBean(TelemetryEventPublisher.class, () -> Mockito.mock(TelemetryEventPublisher.class))
            .withBean(OcrProviderResolver.class, () -> Mockito.mock(OcrProviderResolver.class))
            .withBean(ImageReadingCapture.class)
            .withPropertyValues("ocr.url=https://ocr.example/extract");

    @Test
    void bootsWithASecondProviderAndNoPrimary() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(MeterReadingExtractor.class).hasSize(2);
            assertThat(context).hasSingleBean(ImageReadingCapture.class);
        });
    }

    @Test
    void givesEachChannelItsOwnDefaultProvider() {
        contextRunner.run(context -> {
            OcrProviderRegistry registry = context.getBean(OcrProviderRegistry.class);
            assertThat(registry.get(ReadingChannel.BFM, null))
                    .containsSame(context.getBean(FlowVisionOcrExtractor.class));
            assertThat(registry.get(ReadingChannel.ELM, "elm-vision"))
                    .containsSame(context.getBean(ElmProviderExtractor.class));
            // ELM has no default provider yet, so a tenant with no ELM override gets none, never FlowVision.
            assertThat(registry.get(ReadingChannel.ELM, null)).isEmpty();
        });
    }
}
