package org.arghyam.jalsoochak.analytics.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the unit code shared with telemetry-service over Kafka ({@code MeterReadingEvent.submittedUnit}):
 * telemetry stores only {@code ReadingUnit.code()}, so kVAh always arrives as {@code kV.A.h}.
 */
class MeterRegisterTest {

    @Test
    void kilovoltAmpereHours_isTheApparentEnergyRegister() {
        assertThat(MeterRegister.of("kV.A.h")).isEqualTo(MeterRegister.APPARENT_ENERGY);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"kW.h", "m3", "kL", "L", "min", "h"})
    void everyOtherUnit_andNoUnit_isTheStandardRegister(String submittedUnit) {
        assertThat(MeterRegister.of(submittedUnit)).isEqualTo(MeterRegister.STANDARD);
    }
}
