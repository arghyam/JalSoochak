package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingsDataResponseJsonTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void serializesCorrelationIdAsCamelCase() throws Exception {
        ReadingsDataResponse response = ReadingsDataResponse.builder()
                .correlationId("corr-123")
                .message("ok")
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertTrue(json.has("correlationId"));
        assertEquals("corr-123", json.get("correlationId").asText());
        assertFalse(json.has("correlation_id"));
    }
}
