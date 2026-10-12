package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.channel.MeterRegister;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmittedValueCaptureTest {

    /** Unstubbed, it returns no maximum for any channel. */
    private final ManualReadingMaxValues maxValues = mock(ManualReadingMaxValues.class);

    private final SubmittedValueCapture capture = new SubmittedValueCapture(maxValues);

    private void maximum(ReadingChannel channel, String max) {
        when(maxValues.maxFor(1, channel)).thenReturn(Optional.of(new BigDecimal(max)));
    }

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

    @ParameterizedTest
    @CsvSource({
            "BFM, 12,    m³,    12,    m3",
            "BFM, 1500,  litre, 1.5,   L",
            "BFM, 1500,  liter, 1.5,   L",
            "ELM, 310.2, kWh,   310.2, kW.h",
            "PDU, 1.5,   hr,    90,    h"
    })
    @DisplayName("a unit's other spelling is converted and recorded under its UCUM code")
    void otherSpellingIsRecordedAsTheUcumCode(ReadingChannel channel, String value, String spelling,
                                              String expected, String expectedCode) {
        CapturedReading reading = captured(capture.capture(input(channel, value, spelling, true)));

        assertThat(reading.value()).isEqualByComparingTo(expected);
        assertThat(reading.submittedUnitCode()).isEqualTo(expectedCode);
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

    @ParameterizedTest
    @CsvSource({
            "1440, ",
            "24,   h"
    })
    @DisplayName("a PDU run of up to a day is captured")
    void pduRunOfADayIsCaptured(String value, String unit) {
        CapturedReading reading = captured(capture.capture(input(ReadingChannel.PDU, value, unit, true)));

        assertThat(reading.value()).isEqualByComparingTo("1440");
    }

    @ParameterizedTest
    @CsvSource({
            "1441,  ",
            "24.05, h"
    })
    @DisplayName("a PDU run longer than a day is rejected, measured in minutes after conversion")
    void pduRunLongerThanADayIsRejected(String value, String unit) {
        CaptureOutcome outcome = capture.capture(input(ReadingChannel.PDU, value, unit, true));

        assertThat(outcome).isEqualTo(new CaptureOutcome.Rejected(
                TelemetryErrorCode.ABNORMAL_READING, SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE));
    }

    @Test
    @DisplayName("the day limit is PDU's alone")
    void otherChannelsHaveNoDayLimit() {
        assertThat(captured(capture.capture(input(ReadingChannel.BFM, "5000", null, true))).value())
                .isEqualByComparingTo("5000");
        assertThat(captured(capture.capture(input(ReadingChannel.ELM, "5000", null, true))).value())
                .isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("a correction follows the same unit and limit rules, and is marked MANUAL")
    void correctionFollowsTheSameRules() {
        CapturedReading reading = captured(
                capture.captureCorrection(1, ReadingChannel.BFM, MeterRegister.STANDARD, new BigDecimal("1500"), "L"));
        assertThat(reading.value()).isEqualByComparingTo("1.5");
        assertThat(reading.submittedUnit()).isEqualTo(ReadingUnit.LITRE);
        assertThat(reading.submittedUnitCode()).isEqualTo("L");
        assertThat(reading.source()).isEqualTo(RolloverResolutionService.SOURCE_MANUAL);

        assertThat(capture.captureCorrection(1, ReadingChannel.PDU, MeterRegister.STANDARD, new BigDecimal("25"), "h"))
                .isEqualTo(new CaptureOutcome.Rejected(
                        TelemetryErrorCode.ABNORMAL_READING, SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE));
        assertThat(capture.captureCorrection(
                1, ReadingChannel.PDU, MeterRegister.STANDARD, new BigDecimal("1.5"), "m3"))
                .isInstanceOf(CaptureOutcome.Rejected.class);
    }

    @ParameterizedTest
    @CsvSource({
            "kVAh,   kV.A.h",
            "KVAH,   kV.A.h",
            "kV.A.h, kV.A.h"
    })
    @DisplayName("a kVAh value is kept as it is and recorded under kVAh's UCUM code, on its own register")
    void kvahValueIsKeptAsItIs(String spelling, String expectedCode) {
        CapturedReading reading = captured(capture.capture(input(ReadingChannel.ELM, "4821.75", spelling, true)));

        assertThat(reading.value()).isEqualTo(new BigDecimal("4821.75"));
        assertThat(reading.submittedUnit()).isEqualTo(ReadingUnit.KILOVOLT_AMPERE_HOUR);
        assertThat(reading.submittedUnitCode()).isEqualTo(expectedCode);
        assertThat(reading.register()).isEqualTo(MeterRegister.APPARENT_ENERGY);
    }

    @Test
    @DisplayName("every unit but kVAh is on the standard register, and so is a reading with no unit")
    void otherReadingsAreOnTheStandardRegister() {
        assertThat(captured(capture.capture(input(ReadingChannel.ELM, "4821.75", "kWh", true))).register())
                .isEqualTo(MeterRegister.STANDARD);
        assertThat(captured(capture.capture(input(ReadingChannel.BFM, "1500", "L", true))).register())
                .isEqualTo(MeterRegister.STANDARD);
        assertThat(captured(capture.capture(input(ReadingChannel.MAN, "12", null, true))).register())
                .isEqualTo(MeterRegister.STANDARD);
    }

    @Test
    @DisplayName("a correction with no unit on a kVAh reading stays in kVAh")
    void correctionWithNoUnitKeepsTheRowsKvahRegister() {
        CapturedReading reading = captured(capture.captureCorrection(
                1, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, new BigDecimal("4821.75"), null));

        assertThat(reading.value()).isEqualTo(new BigDecimal("4821.75"));
        assertThat(reading.submittedUnit()).isEqualTo(ReadingUnit.KILOVOLT_AMPERE_HOUR);
    }

    @Test
    @DisplayName("a correction's declared unit wins over the row's register")
    void correctionsDeclaredUnitWinsOverTheRowsRegister() {
        assertThat(captured(capture.captureCorrection(
                1, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, new BigDecimal("4821.75"), "kWh"))
                .submittedUnit()).isEqualTo(ReadingUnit.KILOWATT_HOUR);
        assertThat(captured(capture.captureCorrection(
                1, ReadingChannel.ELM, MeterRegister.STANDARD, new BigDecimal("4821.75"), "kVAh"))
                .submittedUnit()).isEqualTo(ReadingUnit.KILOVOLT_AMPERE_HOUR);
    }

    @ParameterizedTest
    @CsvSource({
            "BFM, m3",
            "ELM, kW.h",
            "PDU, min"
    })
    @DisplayName("a correction with no unit on any other reading is in the channel's standard unit")
    void correctionWithNoUnitOnTheStandardRegisterIsInTheStandardUnit(ReadingChannel channel, String expectedCode) {
        CapturedReading reading = captured(capture.captureCorrection(
                1, channel, MeterRegister.STANDARD, new BigDecimal("45"), null));

        assertThat(reading.submittedUnitCode()).isEqualTo(expectedCode);
    }

    @ParameterizedTest
    @CsvSource({
            "BFM, 50000,   49999.999",
            "BFM, 50000,   50000",
            "ELM, 9999999, 9999999.00",
            "PDU, 720,     720"
    })
    @DisplayName("a value up to the channel's configured maximum is captured")
    void valueUpToTheMaximumIsCaptured(ReadingChannel channel, String max, String value) {
        maximum(channel, max);

        assertThat(captured(capture.capture(input(channel, value, null, false))).value())
                .isEqualByComparingTo(value);
    }

    @ParameterizedTest
    @CsvSource({
            "BFM, 50000,   50000.001, Reading can't be more than 50000 m³.",
            "ELM, 9999999, 10000000,  Reading can't be more than 9999999 kWh.",
            "PDU, 720,     721,       Reading can't be more than 720 minutes.",
            "MAN, 12.50,   13,        Reading can't be more than 12.5."
    })
    @DisplayName("a value above the channel's configured maximum is rejected, stating the maximum")
    void valueAboveTheMaximumIsRejected(ReadingChannel channel, String max, String value, String message) {
        maximum(channel, max);

        assertThat(capture.capture(input(channel, value, null, false)))
                .isEqualTo(new CaptureOutcome.Rejected(TelemetryErrorCode.ABNORMAL_READING, message));
    }

    @Test
    @DisplayName("the maximum is compared after conversion to the channel's standard unit")
    void maximumIsComparedInTheStandardUnit() {
        maximum(ReadingChannel.BFM, "100");

        // 99,000 L is 99 m3 and 101,000 L is 101 m3.
        assertThat(captured(capture.capture(input(ReadingChannel.BFM, "99000", "L", true))).value())
                .isEqualByComparingTo("99");
        assertThat(capture.capture(input(ReadingChannel.BFM, "101000", "L", true)))
                .isInstanceOf(CaptureOutcome.Rejected.class);
    }

    @Test
    @DisplayName("a kVAh value is compared with ELM's maximum as it is, and the maximum stated in kVAh")
    void kvahValueIsComparedWithTheElmMaximumInKvah() {
        maximum(ReadingChannel.ELM, "9999999");

        assertThat(captured(capture.capture(input(ReadingChannel.ELM, "9999999", "kVAh", true))).value())
                .isEqualByComparingTo("9999999");
        assertThat(capture.capture(input(ReadingChannel.ELM, "10000000", "kVAh", true)))
                .isEqualTo(new CaptureOutcome.Rejected(
                        TelemetryErrorCode.ABNORMAL_READING, "Reading can't be more than 9999999 kVAh."));
        assertThat(capture.captureCorrection(
                1, ReadingChannel.ELM, MeterRegister.APPARENT_ENERGY, new BigDecimal("10000000"), null))
                .isEqualTo(new CaptureOutcome.Rejected(
                        TelemetryErrorCode.ABNORMAL_READING, "Reading can't be more than 9999999 kVAh."));
    }

    @Test
    @DisplayName("a configured maximum is looked up for the submission's own tenant and channel")
    void maximumIsTheSubmissionsOwn() {
        when(maxValues.maxFor(any(), any())).thenReturn(Optional.empty());
        when(maxValues.maxFor(2, ReadingChannel.BFM)).thenReturn(Optional.of(BigDecimal.TEN));

        assertThat(capture.capture(input(ReadingChannel.BFM, "11", null, false)))
                .isInstanceOf(CaptureOutcome.Captured.class);
        assertThat(capture.captureCorrection(2, ReadingChannel.BFM, MeterRegister.STANDARD, new BigDecimal("11"), null))
                .isInstanceOf(CaptureOutcome.Rejected.class);
        assertThat(capture.captureCorrection(2, ReadingChannel.ELM, MeterRegister.STANDARD, new BigDecimal("11"), null))
                .isInstanceOf(CaptureOutcome.Captured.class);
    }

    @Test
    @DisplayName("a PDU run longer than a day keeps its own message whatever maximum is configured")
    void pduDayLimitComesFirst() {
        when(maxValues.maxFor(1, ReadingChannel.PDU)).thenReturn(Optional.of(new BigDecimal("720")));

        assertThat(capture.capture(input(ReadingChannel.PDU, "1500", null, false)))
                .isEqualTo(new CaptureOutcome.Rejected(
                        TelemetryErrorCode.ABNORMAL_READING, SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE));
    }

    @Test
    @DisplayName("a correction above the configured maximum is rejected like a submission")
    void correctionAboveTheMaximumIsRejected() {
        maximum(ReadingChannel.BFM, "50000");

        assertThat(capture.captureCorrection(
                1, ReadingChannel.BFM, MeterRegister.STANDARD, new BigDecimal("50001"), null))
                .isEqualTo(new CaptureOutcome.Rejected(
                        TelemetryErrorCode.ABNORMAL_READING, "Reading can't be more than 50000 m³."));
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
