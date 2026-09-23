package org.arghyam.jalsoochak.telemetry.config;

import org.arghyam.jalsoochak.telemetry.provider.whatsapp.glific.GlificMediaFetcher;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.arghyam.jalsoochak.telemetry.service.FlowVisionService;
import org.arghyam.jalsoochak.telemetry.service.InboundMediaService;
import org.arghyam.jalsoochak.telemetry.service.MinioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The media fetch introduces a second {@link RestTemplate} bean into a context where several
 * services inject one by type. Nothing else in this module starts a context, so this is where the
 * ambiguity would otherwise first show up — in production, at boot.
 */
@DisplayName("Media fetch wiring — two RestTemplates, each reaching the right injection point")
class MediaFetchWiringTest {

    @Configuration(proxyBeanMethods = false)
    static class StubCollaborators {
        @Bean
        MinioService minioService() {
            return Mockito.mock(MinioService.class);
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RestTemplateConfig.class, MediaFetchRestTemplateConfig.class,
                    StubCollaborators.class)
            .withBean(GlificMediaFetcher.class)
            .withBean(InboundMediaService.class)
            .withBean(FlowVisionService.class)
            .withPropertyValues("flowvision.url=https://flowvision.example/extract");

    @Test
    void startsWithBothClientsAndNoAmbiguity() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBeans(RestTemplate.class).hasSize(2);
            assertThat(context).hasSingleBean(MediaUrlValidator.class);
        });
    }

    @Test
    void givesTheGuardedClientToTheMediaFetchAndTheSharedOneToEverythingElse() {
        contextRunner.run(context -> {
            RestTemplate shared = (RestTemplate) context.getBean("restTemplate");
            RestTemplate guarded = (RestTemplate) context.getBean("mediaFetchRestTemplate");
            assertThat(shared).isNotSameAs(guarded);

            // A URL on the webhook payload is caller-chosen, so it goes out on the guarded client.
            InboundMediaService mediaService = context.getBean(InboundMediaService.class);
            assertThat(ReflectionTestUtils.getField(mediaService, "mediaFetchRestTemplate")).isSameAs(guarded);

            // A provider media id resolves against a host we configured, so it keeps the shared one.
            GlificMediaFetcher mediaFetcher = context.getBean(GlificMediaFetcher.class);
            assertThat(ReflectionTestUtils.getField(mediaFetcher, "restTemplate")).isSameAs(shared);

            // FlowVision may legitimately sit on an internal address, so it must keep the unguarded
            // client it has always had.
            FlowVisionService flowVisionService = context.getBean(FlowVisionService.class);
            assertThat(ReflectionTestUtils.getField(flowVisionService, "restTemplate")).isSameAs(shared);
        });
    }
}
