package org.arghyam.jalsoochak.telemetry.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingRequestMapperRegistryTest {

    private final CanonicalReadingRequestMapper canonical = new CanonicalReadingRequestMapper(new ObjectMapper());
    private final ReadingRequestMapper stateX = new StubMapper("stateX");

    private ReadingRequestMapperRegistry registry() {
        return new ReadingRequestMapperRegistry(List.of(canonical, stateX));
    }

    @Test
    void resolvesRegisteredFormat() {
        assertSame(stateX, registry().resolve("stateX"));
    }

    @Test
    void resolveIsCaseInsensitiveAndTrimmed() {
        assertSame(stateX, registry().resolve("  STATEX "));
        assertSame(canonical, registry().resolve("CANONICAL"));
    }

    @Test
    void nullOrBlankFormatResolvesToCanonicalDefault() {
        assertSame(canonical, registry().resolve(null));
        assertSame(canonical, registry().resolve("   "));
    }

    @Test
    void unknownFormatThrowsSoCallerCanReturn400() {
        assertThrows(IllegalArgumentException.class, () -> registry().resolve("does-not-exist"));
    }

    @Test
    void supportsReflectsRegistration() {
        ReadingRequestMapperRegistry registry = registry();
        assertTrue(registry.supports("stateX"));
        assertTrue(registry.supports("canonical"));
        assertFalse(registry.supports("nope"));
        assertFalse(registry.supports(null));
        assertFalse(registry.supports("  "));
    }

    @Test
    void duplicateFormatIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new ReadingRequestMapperRegistry(List.of(new StubMapper("dup"), new StubMapper("DUP"))));
    }

    @Test
    void blankFormatIsRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new ReadingRequestMapperRegistry(List.of(new StubMapper("  "))));
    }

    @Test
    void missingCanonicalDefaultThrowsWhenNoFormatRequested() {
        ReadingRequestMapperRegistry registry = new ReadingRequestMapperRegistry(List.of(stateX));
        assertThrows(IllegalStateException.class, () -> registry.resolve(null));
    }

    private static final class StubMapper implements ReadingRequestMapper {
        private final String format;

        private StubMapper(String format) {
            this.format = format;
        }

        @Override
        public String format() {
            return format;
        }

        @Override
        public AssamReadingRequest map(JsonNode rawBody) {
            return new AssamReadingRequest();
        }
    }
}
