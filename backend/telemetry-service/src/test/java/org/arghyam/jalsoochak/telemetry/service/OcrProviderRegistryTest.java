package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
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
        public FlowVisionResult extractReading(String imageUrl, OcrProviderSettings settings) {
            return null;
        }

        @Override
        public FlowVisionResult extractReadingOrThrow(String imageUrl, OcrProviderSettings settings) {
            return null;
        }
    }

    @Test
    void returnsExtractorMatchingProviderId() {
        FakeExtractor flowvision = new FakeExtractor("flowvision");
        FakeExtractor visionX = new FakeExtractor("vision-x");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(flowvision, visionX), "flowvision");

        assertSame(visionX, registry.get("vision-x"));
        assertSame(flowvision, registry.get("flowvision"));
    }

    @Test
    void matchesProviderIdCaseInsensitively() {
        FakeExtractor visionX = new FakeExtractor("Vision-X");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(new FakeExtractor("flowvision"), visionX), "flowvision");

        assertSame(visionX, registry.get("vision-x"));
    }

    @Test
    void fallsBackToDefaultForUnknownProvider() {
        FakeExtractor flowvision = new FakeExtractor("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(flowvision, new FakeExtractor("vision-x")), "flowvision");

        assertSame(flowvision, registry.get("does-not-exist"));
    }

    @Test
    void fallsBackToDefaultForNullProvider() {
        FakeExtractor flowvision = new FakeExtractor("flowvision");
        OcrProviderRegistry registry = new OcrProviderRegistry(List.of(flowvision), "flowvision");

        assertSame(flowvision, registry.get(null));
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
