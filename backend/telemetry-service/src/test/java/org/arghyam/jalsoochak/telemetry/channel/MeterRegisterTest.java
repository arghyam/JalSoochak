package org.arghyam.jalsoochak.telemetry.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MeterRegisterTest {

    @Test
    void of_kvahsStoredCodeIsTheApparentEnergyRegister() {
        assertThat(MeterRegister.of("kV.A.h")).isEqualTo(MeterRegister.APPARENT_ENERGY);
    }

    /**
     * Only {@code ReadingUnit.code()} is ever stored, so matching is exact, as in analytics-service: a
     * spelling alias or another case is a unit that never reaches the table.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"kW.h", "m3", "L", "min", "h", "kVAh", "KV.A.H", " kV.A.h", ""})
    void of_everyOtherValueIsTheStandardRegister(String submittedUnit) {
        assertThat(MeterRegister.of(submittedUnit)).isEqualTo(MeterRegister.STANDARD);
    }
}
