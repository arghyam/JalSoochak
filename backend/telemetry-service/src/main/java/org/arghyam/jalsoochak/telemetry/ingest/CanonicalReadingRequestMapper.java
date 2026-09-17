package org.arghyam.jalsoochak.telemetry.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.springframework.stereotype.Component;

/**
 * Default {@link ReadingRequestMapper}: the incoming body already matches the canonical contract, so
 * this is an identity mapping that simply deserializes it into {@link AssamReadingRequest} (honouring
 * the DTO's {@code @JsonProperty}/{@code @JsonAlias} field aliases). This is also the fallback the
 * registry uses when no explicit format is requested.
 */
@Component
public class CanonicalReadingRequestMapper implements ReadingRequestMapper {

    public static final String FORMAT = "canonical";

    private final ObjectMapper objectMapper;

    public CanonicalReadingRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public AssamReadingRequest map(JsonNode rawBody) {
        if (rawBody == null || rawBody.isNull() || rawBody.isMissingNode()) {
            return new AssamReadingRequest();
        }
        return objectMapper.convertValue(rawBody, AssamReadingRequest.class);
    }
}
