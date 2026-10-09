package org.arghyam.jalsoochak.telemetry.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingUnitTest {

    @Test
    void codes_areTheUcumSpellings() {
        assertThat(ReadingUnit.CUBIC_METRE.code()).isEqualTo("m3");
        assertThat(ReadingUnit.KILOLITRE.code()).isEqualTo("kL");
        assertThat(ReadingUnit.LITRE.code()).isEqualTo("L");
        assertThat(ReadingUnit.KILOWATT_HOUR.code()).isEqualTo("kW.h");
        assertThat(ReadingUnit.KILOVOLT_AMPERE_HOUR.code()).isEqualTo("kV.A.h");
        assertThat(ReadingUnit.MINUTE.code()).isEqualTo("min");
        assertThat(ReadingUnit.HOUR.code()).isEqualTo("h");
    }

    @ParameterizedTest
    @EnumSource(ReadingUnit.class)
    void parseFor_acceptsEveryCodeTrimmedAndInAnyCase(ReadingUnit unit) {
        ReadingChannel channel = unit.channel();

        assertThat(ReadingUnit.parseFor(channel, unit.code())).contains(unit);
        assertThat(ReadingUnit.parseFor(channel, unit.code().toUpperCase(Locale.ROOT))).contains(unit);
        assertThat(ReadingUnit.parseFor(channel, unit.code().toLowerCase(Locale.ROOT))).contains(unit);
        assertThat(ReadingUnit.parseFor(channel, "  " + unit.code() + "  ")).contains(unit);
    }

    @ParameterizedTest
    @CsvSource({
            "BFM, m³,         CUBIC_METRE",
            "BFM, M³,         CUBIC_METRE",
            "BFM, litre,      LITRE",
            "BFM, LITRE,      LITRE",
            "BFM, liter,      LITRE",
            "BFM, Liter,      LITRE",
            "ELM, kWh,        KILOWATT_HOUR",
            "ELM, KWH,        KILOWATT_HOUR",
            "ELM, kwh,        KILOWATT_HOUR",
            "ELM, kVAh,       KILOVOLT_AMPERE_HOUR",
            "ELM, KVAH,       KILOVOLT_AMPERE_HOUR",
            "ELM, kvah,       KILOVOLT_AMPERE_HOUR",
            "PDU, hr,         HOUR",
            "PDU, HR,         HOUR",
            "PDU, '  hr  ',   HOUR"
    })
    void parseFor_acceptsAUnitsOtherSpellingsTrimmedAndInAnyCase(ReadingChannel channel, String spelling,
                                                                 ReadingUnit expected) {
        assertThat(ReadingUnit.parseFor(channel, spelling)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "BFM, kW.h",
            "BFM, kWh",
            "BFM, hr",
            "ELM, m³",
            "ELM, litre",
            "PDU, kWh",
            "PDU, liter",
            "BFM, min",
            "ELM, m3",
            "ELM, h",
            "PDU, L",
            "PDU, kW.h",
            "BFM, kVAh",
            "PDU, kV.A.h",
            "IOT, m3",
            "MAN, min"
    })
    void parseFor_rejectsAUnitOfAnotherChannel(ReadingChannel channel, String code) {
        assertThat(ReadingUnit.parseFor(channel, code)).isEmpty();
    }

    @Test
    void parseFor_rejectsUnknownNullAndBlankCodes() {
        assertThat(ReadingUnit.parseFor(ReadingChannel.BFM, "gallon")).isEmpty();
        assertThat(ReadingUnit.parseFor(ReadingChannel.BFM, "m^3")).isEmpty();
        assertThat(ReadingUnit.parseFor(ReadingChannel.BFM, "litres")).isEmpty();
        assertThat(ReadingUnit.parseFor(ReadingChannel.PDU, "hrs")).isEmpty();
        assertThat(ReadingUnit.parseFor(ReadingChannel.BFM, null)).isEmpty();
        assertThat(ReadingUnit.parseFor(ReadingChannel.BFM, "   ")).isEmpty();
    }

    @Test
    void isDeclared_onlyForANonBlankValue() {
        assertThat(ReadingUnit.isDeclared(null)).isFalse();
        assertThat(ReadingUnit.isDeclared("")).isFalse();
        assertThat(ReadingUnit.isDeclared("   ")).isFalse();
        assertThat(ReadingUnit.isDeclared("m3")).isTrue();
        assertThat(ReadingUnit.isDeclared("nonsense")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "CUBIC_METRE,          950.125,  950.125",
            "KILOLITRE,            12.5,     12.5",
            "LITRE,                1234.5,   1.2345",
            "LITRE,                0.1,      0.0001",
            "KILOWATT_HOUR,        4821.75,  4821.75",
            "KILOVOLT_AMPERE_HOUR, 5310.4,   5310.4",
            "MINUTE,               45,       45",
            "HOUR,                 1.5,      90",
            "HOUR,                 0.0125,   0.75"
    })
    void toStoredUnit_isExact(ReadingUnit unit, BigDecimal value, BigDecimal expected) {
        assertThat(unit.toStoredUnit(value)).isEqualByComparingTo(expected);
    }

    @Test
    void toStoredUnit_keepsAStoredUnitValueUnchanged() {
        BigDecimal value = new BigDecimal("950.10");

        assertThat(ReadingUnit.CUBIC_METRE.toStoredUnit(value)).isEqualTo(value);
        assertThat(ReadingUnit.KILOVOLT_AMPERE_HOUR.toStoredUnit(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @CsvSource({
            "CUBIC_METRE,          CUBIC_METRE",
            "KILOLITRE,            CUBIC_METRE",
            "LITRE,                CUBIC_METRE",
            "KILOWATT_HOUR,        KILOWATT_HOUR",
            "KILOVOLT_AMPERE_HOUR, KILOVOLT_AMPERE_HOUR",
            "MINUTE,               MINUTE",
            "HOUR,                 MINUTE"
    })
    void storedUnit_isTheStandardUnitExceptForKvah(ReadingUnit unit, ReadingUnit expected) {
        assertThat(unit.storedUnit()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(ReadingUnit.class)
    void register_isApparentEnergyForKvahOnly(ReadingUnit unit) {
        assertThat(unit.register()).isEqualTo(unit == ReadingUnit.KILOVOLT_AMPERE_HOUR
                ? MeterRegister.APPARENT_ENERGY
                : MeterRegister.STANDARD);
    }

    @Test
    void everyChannelWithAStandardUnitAcceptsIt() {
        for (ReadingChannel channel : ReadingChannel.values()) {
            channel.standardUnit().ifPresent(unit -> assertThat(unit.channel()).isEqualTo(channel));
        }
    }

    @Test
    void unsupportedMessage_listsTheChannelsUnitsAndNeverTheSubmittedValue() {
        assertThat(ReadingUnit.unsupportedMessage(ReadingChannel.BFM))
                .isEqualTo("Unsupported reading_unit for channel BFM. Allowed values are: m3, kL, L");
        assertThat(ReadingUnit.unsupportedMessage(ReadingChannel.ELM))
                .isEqualTo("Unsupported reading_unit for channel ELM. Allowed values are: kW.h, kV.A.h");
        assertThat(ReadingUnit.unsupportedMessage(ReadingChannel.PDU))
                .isEqualTo("Unsupported reading_unit for channel PDU. Allowed values are: min, h");
    }

    @Test
    void unsupportedMessage_forAChannelWithNoUnits() {
        assertThat(ReadingUnit.unsupportedMessage(ReadingChannel.IOT))
                .isEqualTo("Channel IOT does not accept a reading_unit.");
    }
}
