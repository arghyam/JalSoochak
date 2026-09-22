package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape of {@link CreateReadingResponse}, which is shared by two very different consumers:
 * the Glific flow's webhooks and the state IT system's {@code POST /api/v1/telemetry/readings}.
 *
 * <p>These tests exist because that sharing makes the class easy to break in one direction while
 * fixing the other. It carries no {@code @JsonInclude(NON_NULL)} and the service sets no global
 * inclusion policy, so nulls are currently on the wire — adding the annotation to tidy up a new
 * field would silently delete keys from a live vendor integration's payload.
 */
@DisplayName("CreateReadingResponse — wire shape")
class CreateReadingResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode serialize(CreateReadingResponse response) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(response));
    }

    @Test
    @DisplayName("locationMismatch is always present and defaults to false")
    void locationMismatchIsAlwaysPresent() throws Exception {
        JsonNode json = serialize(CreateReadingResponse.builder().success(true).build());

        // A boxed Boolean here would serialize as null on every response this class produces,
        // including every successful vendor reading. Primitive keeps the addition invisible.
        assertThat(json.has("locationMismatch")).isTrue();
        assertThat(json.get("locationMismatch").isNull()).isFalse();
        assertThat(json.get("locationMismatch").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("locationMismatch is true when the check fired")
    void locationMismatchIsTrueOnAMismatch() throws Exception {
        JsonNode json = serialize(CreateReadingResponse.builder()
                .success(true)
                .locationMismatch(true)
                .qualityStatus("CONFIRMED")
                .build());

        assertThat(json.get("locationMismatch").asBoolean()).isTrue();
        // A mismatch is not a failure — the flow branches on locationMismatch, never on success.
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("errorCode").isNull()).isTrue();
    }

    @Test
    @DisplayName("null-valued keys are still emitted, which the state IT system's payload relies on")
    void nullKeysAreStillEmitted() throws Exception {
        // Pins the absence of @JsonInclude(NON_NULL). Adding it would be a breaking change to
        // POST /api/v1/telemetry/readings, whose successful responses carry "errorCode": null today.
        JsonNode json = serialize(CreateReadingResponse.builder().success(true).build());

        assertThat(json.has("errorCode")).isTrue();
        assertThat(json.has("correlationId")).isTrue();
        assertThat(json.has("meterReading")).isTrue();
        assertThat(json.has("qualityStatus")).isTrue();
        assertThat(json.has("qualityConfidence")).isTrue();
        assertThat(json.has("lastConfirmedReading")).isTrue();
        assertThat(json.has("message")).isTrue();
    }

    @Test
    @DisplayName("a fully populated response keeps every existing field name")
    void fieldNamesAreStable() throws Exception {
        JsonNode json = serialize(CreateReadingResponse.builder()
                .correlationId("abc")
                .meterReading(new BigDecimal("12.5"))
                .qualityStatus("CONFIRMED")
                .qualityConfidence(new BigDecimal("98"))
                .lastConfirmedReading(new BigDecimal("11.0"))
                .success(true)
                .locationMismatch(false)
                .errorCode(TelemetryErrorCode.ABNORMAL_READING)
                .message("ok")
                .build());

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "correlationId", "meterReading", "qualityStatus", "qualityConfidence",
                "lastConfirmedReading", "success", "locationMismatch", "errorCode", "message");
    }
}
