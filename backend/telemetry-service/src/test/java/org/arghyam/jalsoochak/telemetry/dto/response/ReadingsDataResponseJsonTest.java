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
                .errorCode(TelemetryErrorCode.UNREADABLE_IMAGE)
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertTrue(json.has("correlationId"));
        assertEquals("corr-123", json.get("correlationId").asText());
        assertFalse(json.has("correlation_id"));
        assertFalse(json.has("errorCode"));
        assertEquals("UNREADABLE_IMAGE", json.get("error_code").asText());
    }

    @Test
    void serializesCreateReadingErrorCodeAsSnakeCase() throws Exception {
        CreateReadingResponse response = CreateReadingResponse.builder()
                .success(false)
                .errorCode(TelemetryErrorCode.FLOW_VISION_FAILED)
                .message("failed")
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertFalse(json.has("errorCode"));
        assertEquals("FLOW_VISION_FAILED", json.get("error_code").asText());
    }
}
