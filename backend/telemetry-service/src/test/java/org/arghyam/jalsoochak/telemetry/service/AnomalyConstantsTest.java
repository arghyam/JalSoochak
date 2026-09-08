package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AnomalyConstants")
class AnomalyConstantsTest {

    @Nested
    @DisplayName("type codes")
    class TypeCodes {

        @Test
        @DisplayName("the implausible-supply anomaly is type 10")
        void implausibleSupplyIsTen() {
            // Mirrored by EscalationType.IMPLAUSIBLE_WATER_SUPPLY in analytics-service, which cannot
            // be imported here. Both sides are pinned to the literal so a change to either fails.
            assertThat(AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY).isEqualTo(10);
        }

        @Test
        @DisplayName("is distinct from the tolerance-based over-supply anomaly")
        void isDistinctFromOverWaterSupply() {
            // Type 8 means "above the tenant's configured tolerance over the norm"; type 10 means
            // "physically impossible for this population". Collapsing them would lose that.
            assertThat(AnomalyConstants.TYPE_IMPLAUSIBLE_WATER_SUPPLY)
                    .isNotEqualTo(AnomalyConstants.TYPE_OVER_WATER_SUPPLY);
        }

        @Test
        @DisplayName("codes are unique and contiguous from 1")
        void codesAreUniqueAndContiguous() {
            List<Integer> codes = intConstants("TYPE_");

            assertThat(codes).doesNotHaveDuplicates();
            assertThat(codes).containsExactlyInAnyOrderElementsOf(
                    Stream.iterate(1, i -> i + 1).limit(codes.size()).toList());
        }
    }

    @Nested
    @DisplayName("reason texts")
    class ReasonTexts {

        static Stream<String> supplyReasons() {
            return Stream.of(
                    AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_SUBMITTED,
                    AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED,
                    AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_QUARANTINED);
        }

        @Test
        @DisplayName("case A names the submission that was quarantined")
        void caseA() {
            assertThat(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_SUBMITTED)
                    .isEqualTo("Submitted reading implies an implausible daily water supply for this scheme.");
        }

        @Test
        @DisplayName("case B says the published reading still stands")
        void caseB() {
            assertThat(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED)
                    .isEqualTo("Correction rejected: implies an implausible daily water supply. "
                            + "The published reading is unchanged.");
        }

        @Test
        @DisplayName("case C says the day is still missing from analytics")
        void caseC() {
            assertThat(AnomalyConstants.REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_QUARANTINED)
                    .isEqualTo("Correction rejected: implies an implausible daily water supply. "
                            + "The reading remains quarantined.");
        }

        @Test
        @DisplayName("the three cases are distinguishable from one another")
        void casesAreDistinct() {
            // anomaly_table carries no other marker for a refused correction, so if two of these
            // ever collide the cases become indistinguishable in the data.
            assertThat(supplyReasons().toList()).doesNotHaveDuplicates();
        }

        @ParameterizedTest
        @MethodSource("supplyReasons")
        @DisplayName("carry no interpolated values, so the column stays groupable")
        void carryNoInterpolatedValues(String reason) {
            // A per-row number appended here would make "12 corrections rejected" a text parse
            // rather than a GROUP BY. The numbers belong in overridden_reading / previous_reading.
            assertThat(reason)
                    .doesNotContainPattern("\\d")
                    .doesNotContain("%s", "%d", "{}", "{0}");
        }
    }

    /** Reads the {@code public static final int} constants whose name starts with {@code prefix}. */
    private static List<Integer> intConstants(String prefix) {
        List<Integer> values = new ArrayList<>();
        for (Field field : AnomalyConstants.class.getDeclaredFields()) {
            if (field.getType() == int.class
                    && Modifier.isStatic(field.getModifiers())
                    && field.getName().startsWith(prefix)) {
                try {
                    values.add(field.getInt(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Could not read " + field.getName(), e);
                }
            }
        }
        return values;
    }
}
