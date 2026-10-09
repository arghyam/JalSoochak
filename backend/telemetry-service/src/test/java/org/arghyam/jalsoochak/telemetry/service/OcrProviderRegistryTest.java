package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrProviderRegistryTest {

    private static final class FakeExtractor implements MeterReadingExtractor {
        private final String id;
        private final ReadingChannel channel;

        FakeExtractor(String id, ReadingChannel channel) {
            this.id = id;
            this.channel = channel;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public ReadingChannel channel() {
            return channel;
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

    private static FakeExtractor bfm(String id) {
        return new FakeExtractor(id, ReadingChannel.BFM);
    }

    private static FakeExtractor elm(String id) {
        return new FakeExtractor(id, ReadingChannel.ELM);
    }

    @Test
    void returnsExtractorMatchingProviderId() {
        FakeExtractor builtIn = bfm("flowvision");
        FakeExtractor visionX = bfm("vision-x");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn, visionX), "flowvision", null);

        assertThat(registry.get(ReadingChannel.BFM, "vision-x")).containsSame(visionX);
        assertThat(registry.get(ReadingChannel.BFM, "flowvision")).containsSame(builtIn);
    }

    @Test
    void matchesProviderIdCaseInsensitively() {
        FakeExtractor visionX = bfm("Vision-X");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(bfm("flowvision"), visionX), "flowvision", null);

        assertThat(registry.get(ReadingChannel.BFM, "vision-x")).containsSame(visionX);
    }

    @Test
    void fallsBackToBfmDefaultForUnknownProvider() {
        FakeExtractor builtIn = bfm("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn, bfm("vision-x")), "flowvision", null);

        assertThat(registry.get(ReadingChannel.BFM, "does-not-exist")).containsSame(builtIn);
    }

    @Test
    void fallsBackToBfmDefaultForNullProvider() {
        FakeExtractor builtIn = bfm("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn), "flowvision", null);

        assertThat(registry.get(ReadingChannel.BFM, null)).containsSame(builtIn);
    }

    @Test
    void keepsFirstRegistrationOnDuplicateId() {
        FakeExtractor first = bfm("flowvision");
        FakeExtractor second = bfm("FlowVision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(first, second), "flowvision", null);

        assertThat(registry.get(ReadingChannel.BFM, "flowvision")).containsSame(first);
    }

    @Test
    void throwsWhenBfmDefaultProviderMissing() {
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(bfm("vision-x")), "flowvision", null);

        assertThrows(IllegalStateException.class, () -> registry.get(ReadingChannel.BFM, "unknown-provider"));
    }

    @Test
    void returnsTheTenantsProviderForItsOwnChannel() {
        FakeExtractor elmVision = elm("elm-vision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(bfm("flowvision"), elmVision), "flowvision", null);

        assertThat(registry.get(ReadingChannel.ELM, "ELM-Vision")).containsSame(elmVision);
    }

    @Test
    void neverServesAChannelWithAnotherChannelsProvider() {
        FakeExtractor builtIn = bfm("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn, elm("elm-vision")), "flowvision", null);

        assertThat(registry.get(ReadingChannel.ELM, "flowvision")).isEmpty();
        assertThat(registry.get(ReadingChannel.BFM, "elm-vision")).containsSame(builtIn);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void elmHasNoDefaultProviderUntilOneIsConfigured(String elmDefaultProviderId) {
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(bfm("flowvision"), elm("elm-vision")), "flowvision", elmDefaultProviderId);

        assertThat(registry.get(ReadingChannel.ELM, null)).isEmpty();
        assertThat(registry.get(ReadingChannel.ELM, "does-not-exist")).isEmpty();
    }

    @Test
    void servesElmItsConfiguredDefaultForNoProvider() {
        FakeExtractor elmVision = elm("elm-vision");
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(bfm("flowvision"), elmVision), "flowvision", "elm-vision");

        assertThat(registry.get(ReadingChannel.ELM, null)).containsSame(elmVision);
    }

    @Test
    void fallsBackToElmDefaultForUnknownProvider() {
        FakeExtractor elmVision = elm("elm-vision");
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(bfm("flowvision"), elmVision, elm("elm-other")), "flowvision", "elm-vision");

        assertThat(registry.get(ReadingChannel.ELM, "does-not-exist")).containsSame(elmVision);
    }

    @Test
    void prefersTheTenantsElmProviderOverTheElmDefault() {
        FakeExtractor elmOther = elm("elm-other");
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(bfm("flowvision"), elm("elm-vision"), elmOther), "flowvision", "elm-vision");

        assertThat(registry.get(ReadingChannel.ELM, "elm-other")).containsSame(elmOther);
    }

    @Test
    void matchesTheElmDefaultCaseInsensitively() {
        FakeExtractor elmVision = elm("elm-vision");
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(bfm("flowvision"), elmVision), "flowvision", " ELM-Vision ");

        assertThat(registry.get(ReadingChannel.ELM, null)).containsSame(elmVision);
    }

    @Test
    void neverServesBfmWithTheElmDefault() {
        FakeExtractor builtIn = bfm("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(builtIn, elm("elm-vision")), "flowvision", "elm-vision");

        assertThat(registry.get(ReadingChannel.BFM, null)).containsSame(builtIn);
        assertThat(registry.get(ReadingChannel.BFM, "elm-vision")).containsSame(builtIn);
    }

    @Test
    void refusesToStartWhenTheElmDefaultIsNotRegistered() {
        List<MeterReadingExtractor> extractors = List.of(bfm("flowvision"), elm("elm-vision"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new OcrProviderRegistry(extractors, "flowvision", "elm-typo"));
        assertThat(ex).hasMessageContaining("elm-typo");
    }

    @Test
    void refusesToStartWhenTheElmDefaultIsRegisteredOnlyForAnotherChannel() {
        List<MeterReadingExtractor> extractors = List.of(bfm("flowvision"));

        assertThrows(IllegalStateException.class,
                () -> new OcrProviderRegistry(extractors, "flowvision", "flowvision"));
    }

    @Test
    void aChannelWithNoProviderHasNothingEvenForTheBfmDefaultId() {
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(bfm("flowvision")), "flowvision", null);

        assertThat(registry.get(ReadingChannel.ELM, null)).isEmpty();
        assertThat(registry.get(ReadingChannel.ELM, "flowvision")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReadingChannel.class, names = {"PDU", "IOT", "MAN"})
    void ignoresAProviderForAChannelThatDoesNotReadPhotos(ReadingChannel channel) {
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(bfm("flowvision"), new FakeExtractor("typed-in", channel)), "flowvision", null);

        assertThat(registry.get(channel, "typed-in")).isEmpty();
    }

    @Test
    void ignoresAProviderThatDeclaresNoChannel() {
        FakeExtractor builtIn = bfm("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(
                List.of(builtIn, new FakeExtractor("vision-x", null)), "flowvision", null);

        assertThat(registry.get(ReadingChannel.BFM, "vision-x")).containsSame(builtIn);
    }
}
