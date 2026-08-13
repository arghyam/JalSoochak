package org.arghyam.jalsoochak.telemetry.ingest;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the {@link ReadingRequestMapper} for a requested {@code format}. All mapper beans are
 * discovered automatically by Spring, so a new state format becomes plug-and-play: declare a
 * {@code @Component} implementing {@link ReadingRequestMapper} and it is registered here on startup.
 */
@Component
public class ReadingRequestMapperRegistry {

    private final Map<String, ReadingRequestMapper> byFormat;
    private final ReadingRequestMapper defaultMapper;

    public ReadingRequestMapperRegistry(List<ReadingRequestMapper> mappers) {
        Map<String, ReadingRequestMapper> map = new HashMap<>();
        for (ReadingRequestMapper mapper : mappers) {
            String format = mapper.format();
            if (format == null || format.isBlank()) {
                throw new IllegalStateException(
                        "ReadingRequestMapper " + mapper.getClass().getName() + " declares a null/blank format()");
            }
            String key = normalize(format);
            ReadingRequestMapper existing = map.putIfAbsent(key, mapper);
            if (existing != null) {
                throw new IllegalStateException("Duplicate ReadingRequestMapper format '" + key + "': "
                        + existing.getClass().getName() + " and " + mapper.getClass().getName());
            }
        }
        this.byFormat = Map.copyOf(map);
        this.defaultMapper = this.byFormat.get(CanonicalReadingRequestMapper.FORMAT);
    }

    /**
     * Returns the mapper for {@code format}. A null/blank format resolves to the canonical default.
     * An explicitly provided but unregistered format throws {@link IllegalArgumentException} so the
     * caller can surface a clear 400 rather than silently mis-parsing the payload.
     */
    public ReadingRequestMapper resolve(String format) {
        if (format == null || format.isBlank()) {
            return requireDefault();
        }
        ReadingRequestMapper mapper = byFormat.get(normalize(format));
        if (mapper != null) {
            return mapper;
        }
        throw new IllegalArgumentException("No reading request mapper registered for format '" + format + "'");
    }

    /** Whether a mapper is registered for the given (non-blank) format. */
    public boolean supports(String format) {
        return format != null && !format.isBlank() && byFormat.containsKey(normalize(format));
    }

    private ReadingRequestMapper requireDefault() {
        if (defaultMapper == null) {
            throw new IllegalStateException(
                    "No canonical ReadingRequestMapper ('" + CanonicalReadingRequestMapper.FORMAT + "') is registered");
        }
        return defaultMapper;
    }

    private static String normalize(String format) {
        return format.trim().toLowerCase(Locale.ROOT);
    }
}
