package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
        assertFalse(json.has("error_code"));
        assertEquals("UNREADABLE_IMAGE", json.get("errorCode").asText());
    }

    @Test
    void serializesCreateReadingErrorCodeAsCamelCase() throws Exception {
        CreateReadingResponse response = CreateReadingResponse.builder()
                .success(false)
                .errorCode(TelemetryErrorCode.FLOW_VISION_FAILED)
                .message("failed")
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertFalse(json.has("error_code"));
        assertEquals("FLOW_VISION_FAILED", json.get("errorCode").asText());
    }

    /**
     * CreateReadingResponse is the wire body of three Glific webhook routes — /manual-reading,
     * /location and /update-previous-reading — so anything on it is readable by whoever can call
     * them. lastConfirmedReading is the operator's current confirmed meter value; it is carried on
     * this object only so the partner controllers can copy it into ReadingsDataResponse, and the
     * Glific flows never read it (they consume @results.<name>.message and the HTTP status alone).
     */
    @Test
    void doesNotSerializeLastConfirmedReadingOnTheGlificWebhookBody() throws Exception {
        CreateReadingResponse response = CreateReadingResponse.builder()
                .success(false)
                .message("Reading rejected because it is above the allowed maximum for this scheme. Submitted: 77777.")
                .lastConfirmedReading(new BigDecimal("150"))
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertFalse(json.has("lastConfirmedReading"));
        assertFalse(json.has("last_confirmed_reading"));
        // Still reachable in-process: the partner controllers map it across by getter.
        assertEquals(new BigDecimal("150"), response.getLastConfirmedReading());
    }

    /**
     * The operator's WhatsApp text is unaffected. "Your last confirmed reading was N" is built into
     * the separate {@code message} field by BfmReadingService, and the Glific flow renders exactly
     * that field — suppressing the {@code lastConfirmedReading} property does not touch it. The
     * operator is the owner of that number and is being shown their own reading back on a channel
     * they already hold, which is why the sentence stays.
     */
    @Test
    void stillSerializesTheOperatorFacingMessage() throws Exception {
        CreateReadingResponse response = CreateReadingResponse.builder()
                .success(true)
                .message("Reading captured successfully. Extracted reading: 260."
                        + " Your last confirmed reading was 150.")
                .lastConfirmedReading(new BigDecimal("150"))
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertTrue(json.has("message"));
        assertTrue(json.get("message").asText().contains("Your last confirmed reading was 150."));
        assertFalse(json.has("lastConfirmedReading"));
    }

    /**
     * The partner contract is unchanged. ReadingsDataResponse is served only on the X-Api-Key
     * ingestion routes, where the caller holds the tenant's own key and the value is their own
     * operator's reading.
     */
    @Test
    void stillSerializesLastConfirmedReadingOnThePartnerBody() throws Exception {
        ReadingsDataResponse response = ReadingsDataResponse.builder()
                .lastConfirmedReading(new BigDecimal("150"))
                .message("ok")
                .build();

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(response));
        assertTrue(json.has("lastConfirmedReading"));
        assertEquals("150", json.get("lastConfirmedReading").asText());
    }
}
