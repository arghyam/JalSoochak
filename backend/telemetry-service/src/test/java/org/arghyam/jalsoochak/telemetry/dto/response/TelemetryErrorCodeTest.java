package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TelemetryErrorCode")
class TelemetryErrorCodeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(TelemetryErrorCode.class)
    @DisplayName("every wire value mirrors its constant name")
    void wireValueMirrorsConstantName(TelemetryErrorCode code) {
        assertThat(code.code()).isEqualTo(code.name());
    }

    @Test
    @DisplayName("wire values are unique")
    void wireValuesAreUnique() {
        assertThat(Arrays.stream(TelemetryErrorCode.values()).map(TelemetryErrorCode::code).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("ABNORMAL_READING serializes to its stable wire value")
    void abnormalReadingSerializes() throws Exception {
        assertThat(objectMapper.writeValueAsString(TelemetryErrorCode.ABNORMAL_READING))
                .isEqualTo("\"ABNORMAL_READING\"");
    }

    @Test
    @DisplayName("ABNORMAL_READING does not disclose why the reading was refused")
    void abnormalReadingDisclosesNothing() {
        // Deliberately not named after the internal anomaly (IMPLAUSIBLE_WATER_SUPPLY): the wire name
        // must not tell a caller the rejection is volume-derived, which is the first step toward
        // solving for the scheme's connection count and the per-person limit. If someone "tidies" the
        // two names into one, this fails.
        assertThat(TelemetryErrorCode.ABNORMAL_READING.code())
                .doesNotContainIgnoringCase("implausible")
                .doesNotContainIgnoringCase("supply")
                .doesNotContainIgnoringCase("water")
                .doesNotContainIgnoringCase("population")
                .doesNotContainIgnoringCase("threshold");
    }
}
