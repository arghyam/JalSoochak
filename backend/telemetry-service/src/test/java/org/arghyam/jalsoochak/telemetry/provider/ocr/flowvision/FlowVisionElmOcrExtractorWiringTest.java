package org.arghyam.jalsoochak.telemetry.provider.ocr.flowvision;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.config.ElmOcrRestTemplateConfig;
import org.arghyam.jalsoochak.telemetry.config.RestTemplateConfig;
import org.arghyam.jalsoochak.telemetry.service.OcrProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ELM extractor adds a second OCR client to a context where everything else injects the shared
 * {@link RestTemplate} by type. Each extractor has to get the client sized for its provider.
 */
@DisplayName("ELM OCR wiring — its own client and its own ocr.elm settings")
class FlowVisionElmOcrExtractorWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(RestTemplateConfig.class, ElmOcrRestTemplateConfig.class)
            .withBean(FlowVisionBfmOcrExtractor.class)
            .withBean(FlowVisionElmOcrExtractor.class)
            .withPropertyValues("ocr.url=https://ocr.example/extract");

    @Test
    void givesTheElmExtractorItsOwnClientAndLeavesBfmOnTheSharedOne() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RestTemplate shared = (RestTemplate) context.getBean("restTemplate");
            RestTemplate elm = (RestTemplate) context.getBean("elmOcrRestTemplate");
            assertThat(elm).isNotSameAs(shared);

            assertThat(ReflectionTestUtils.getField(context.getBean(FlowVisionElmOcrExtractor.class), "restTemplate"))
                    .isSameAs(elm);
            assertThat(ReflectionTestUtils.getField(context.getBean(FlowVisionBfmOcrExtractor.class), "restTemplate"))
                    .isSameAs(shared);
        });
    }

    @Test
    void bootsWithNoElmSettingsAndSendsTheKeyInXApiKeyByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            FlowVisionElmOcrExtractor extractor = context.getBean(FlowVisionElmOcrExtractor.class);
            assertThat(ReflectionTestUtils.getField(extractor, "defaultEndpointUrl")).isNull();
            assertThat(ReflectionTestUtils.getField(extractor, "defaultApiKey")).isNull();
            assertThat(ReflectionTestUtils.getField(extractor, "defaultAuthHeader")).isEqualTo("X-API-Key");
        });
    }

    @Test
    void isElmsDefaultProviderInTheShippedConfigOnceOcrElmUrlIsSet() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(OcrProviderRegistry.class)
                .withPropertyValues("OCR_ELM_URL=https://elm.example/extract")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlowVisionElmOcrExtractor extractor = context.getBean(FlowVisionElmOcrExtractor.class);
                    assertThat(context.getBean(OcrProviderRegistry.class).get(ReadingChannel.ELM, null))
                            .containsSame(extractor);
                    assertThat(ReflectionTestUtils.getField(extractor, "defaultEndpointUrl"))
                            .isEqualTo("https://elm.example/extract");
                });
    }

    @Test
    void failsToStartWithTheShippedConfigWhenOcrElmUrlIsUnset() {
        contextRunner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withBean(OcrProviderRegistry.class)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("OCR_ELM_URL"));
    }

    @Test
    void readsItsSettingsFromOcrElmAndNeverFromBfmsOcrBlock() {
        contextRunner
                .withPropertyValues(
                        "ocr.api-key=bfm-key",
                        "ocr.auth-header=Authorization",
                        "ocr.elm.url=https://elm.example/extract",
                        "ocr.elm.api-key=elm-key",
                        "ocr.elm.auth-header=X-Elm-Key")
                .run(context -> {
                    FlowVisionElmOcrExtractor extractor = context.getBean(FlowVisionElmOcrExtractor.class);
                    assertThat(ReflectionTestUtils.getField(extractor, "defaultEndpointUrl"))
                            .isEqualTo("https://elm.example/extract");
                    assertThat(ReflectionTestUtils.getField(extractor, "defaultApiKey")).isEqualTo("elm-key");
                    assertThat(ReflectionTestUtils.getField(extractor, "defaultAuthHeader")).isEqualTo("X-Elm-Key");
                });
    }
}
