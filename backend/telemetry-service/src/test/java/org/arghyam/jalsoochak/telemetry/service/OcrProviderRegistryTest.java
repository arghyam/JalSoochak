package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrProviderRegistryTest {

    private static final class FakeExtractor implements MeterReadingExtractor {
        private final String id;

        FakeExtractor(String id) {
            this.id = id;
        }

        @Override
        public String providerId() {
            return id;
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

    @Test
    void returnsExtractorMatchingProviderId() {
        FakeExtractor builtIn = new FakeExtractor("flowvision");
        FakeExtractor visionX = new FakeExtractor("vision-x");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn, visionX), "flowvision");

        assertSame(visionX, registry.get("vision-x"));
        assertSame(builtIn, registry.get("flowvision"));
    }

    @Test
    void matchesProviderIdCaseInsensitively() {
        FakeExtractor visionX = new FakeExtractor("Vision-X");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(new FakeExtractor("flowvision"), visionX), "flowvision");

        assertSame(visionX, registry.get("vision-x"));
    }

    @Test
    void fallsBackToDefaultForUnknownProvider() {
        FakeExtractor builtIn = new FakeExtractor("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn, new FakeExtractor("vision-x")), "flowvision");

        assertSame(builtIn, registry.get("does-not-exist"));
    }

    @Test
    void fallsBackToDefaultForNullProvider() {
        FakeExtractor builtIn = new FakeExtractor("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(builtIn), "flowvision");

        assertSame(builtIn, registry.get(null));
    }

    @Test
    void keepsFirstRegistrationOnDuplicateId() {
        FakeExtractor first = new FakeExtractor("flowvision");
        FakeExtractor second = new FakeExtractor("FlowVision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(first, second), "flowvision");

        assertSame(first, registry.get("flowvision"));
    }

    @Test
    void throwsWhenDefaultProviderMissing() {
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(new FakeExtractor("vision-x")), "flowvision");

        assertThrows(IllegalStateException.class, () -> registry.get("unknown-provider"));
    }
}
