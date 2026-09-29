package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingUnit;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.service.OcrRetryMode;
import org.arghyam.jalsoochak.telemetry.service.RolloverResolutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SubmittedValueCaptureTest {

    private final SubmittedValueCapture capture = new SubmittedValueCapture();

    @Test
    @DisplayName("with no unit declared the value is taken as the channel's standard unit")
    void noUnitMeansTheStandardUnit() {
        CapturedReading reading = captured(capture.capture(input(ReadingChannel.ELM, "4821.75", null, true)));

        assertThat(reading.value()).isEqualTo(new BigDecimal("4821.75"));
        assertThat(reading.submittedUnit()).isEqualTo(ReadingUnit.KILOWATT_HOUR);
    }

    @Test
    @DisplayName("a blank unit counts as not declared")
    void blankUnitMeansTheStandardUnit() {
        CapturedReading reading = captured(capture.capture(input(ReadingChannel.PDU, "45", "  ", true)));

        assertThat(reading.value()).isEqualTo(new BigDecimal("45"));
        assertThat(reading.submittedUnit()).isEqualTo(ReadingUnit.MINUTE);
    }

    @ParameterizedTest
    @CsvSource({
            "BFM, 1500,  L,    1.5,  LITRE",
            "BFM, 12.5,  KL,   12.5, KILOLITRE",
            "PDU, 1.5,   h,    90,   HOUR",
            "PDU, 45,    MIN,  45,   MINUTE",
            "ELM, 310.2, kw.h, 310.2, KILOWATT_HOUR"
    })
    @DisplayName("a declared unit is recorded and the value converted to the standard unit")
    void declaredUnitIsConverted(ReadingChannel channel, String value, String unit, String expected,
                                 ReadingUnit expectedUnit) {
        CapturedReading reading = captured(capture.capture(input(channel, value, unit, true)));

        assertThat(reading.value()).isEqualByComparingTo(expected);
        assertThat(reading.submittedUnit()).isEqualTo(expectedUnit);
    }

    @Test
    @DisplayName("a unit of another channel is rejected, without echoing the value sent")
    void unitOfAnotherChannelIsRejected() {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.PDU, "45", "m3", true));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                "Unsupported reading_unit for channel PDU. Allowed values are: min, h"));
    }

    @Test
    @DisplayName("an unknown unit is rejected")
    void unknownUnitIsRejected() {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.BFM, "950", "gallon", true));

        assertThat(outcome).isInstanceOf(CaptureOutcome.Rejected.class);
        assertThat(((CaptureOutcome.Rejected) outcome).errorCode())
                .isEqualTo(TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("a channel with no units takes a value with no unit and records none")
    void channelWithNoUnitsRecordsNone() {
        CapturedReading reading = captured(capture.capture(input(ReadingChannel.MAN, "12", null, true)));

        assertThat(reading.value()).isEqualTo(new BigDecimal("12"));
        assertThat(reading.submittedUnit()).isNull();
    }

    @Test
    @DisplayName("a channel with no units rejects a declared unit")
    void channelWithNoUnitsRejectsAUnit() {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.IOT, "12", "m3", true));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED,
                "Channel IOT does not accept a reading_unit."));
    }

    @Test
    @DisplayName("nothing is extracted from a submitted value")
    void nothingIsExtracted() {
        CapturedReading reading = captured(capture.capture(input(ReadingChannel.BFM, "950", null, true)));

        assertThat(reading.extractedReading()).isNull();
        assertThat(reading.confidence()).isNull();
        assertThat(reading.ocrResult()).isNull();
    }

    @Test
    @DisplayName("an asserted value is marked EXTERNALLY_ASSERTED and a typed-in one MANUAL")
    void sourceFollowsHowTheValueArrived() {
        assertThat(captured(capture.capture(input(ReadingChannel.BFM, "950", null, true))).source())
                .isEqualTo(RolloverResolutionService.SOURCE_EXTERNALLY_ASSERTED);
        assertThat(captured(capture.capture(input(ReadingChannel.BFM, "950", null, false))).source())
                .isEqualTo(RolloverResolutionService.SOURCE_MANUAL);
    }

    private static CapturedReading captured(CaptureOutcome outcome) {
        assertThat(outcome).isInstanceOf(CaptureOutcome.Captured.class);
        return ((CaptureOutcome.Captured) outcome).reading();
    }

    private static CaptureInput input(ReadingChannel channel, String value, String unit, boolean asserted) {
        return new CaptureInput("tenant_test", 1, 11L, 100L, channel, null, new BigDecimal(value), unit,
                asserted, OcrRetryMode.NONE);
    }
}
